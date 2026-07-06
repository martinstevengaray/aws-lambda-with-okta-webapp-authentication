package com.example.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;
import com.okta.jwt.JwtVerifiers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class OktaDelegate {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    private static final String TOKEN_COOKIE = "okta_token";
    private static final String STATE_COOKIE = "oauth_state";
    private static final String CALLBACK_PATH = "/callback";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AccessTokenVerifier verifier;


    private final String oktaIssuer;
    private final String oktaWebClientId;
    private final String oktaWebClientSecret;
    private final String oktaScopes;

    public OktaDelegate(String oktaIssuer, String oktaAudience, String oktaWebClientId, String oktaWebClientSecret, String oktaScopes) {
        this.oktaIssuer = oktaIssuer;
        this.oktaWebClientId = oktaWebClientId;
        this.oktaWebClientSecret = oktaWebClientSecret;
        this.oktaScopes = oktaScopes;

        verifier = JwtVerifiers.accessTokenVerifierBuilder()
                .setIssuer(oktaIssuer)
                .setAudience(oktaAudience)
                .setConnectionTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Jwt decode(String token) throws JwtVerificationException {
        return verifier.decode(token);
    }

    /**
     * No valid token: finish the OIDC flow on /callback, start it for
     * browsers, and keep the plain 401 for API clients.
     */
    public Map<String, Object> unauthenticated(Map<String, Object> event, Context context) {
        String path = (String) LambdaUtils.asMap(LambdaUtils.asMap(event.get("requestContext")).get("http")).get("path");

        if (CALLBACK_PATH.equals(path)) {
            return callback(event, context);
        }
        if (LambdaUtils.acceptsHtml(event) && oidcConfigured()) {
            return redirectToOkta(event, path);
        }
        return LambdaUtils.response(401,
                Map.of("content-type", "application/json",
                        "www-authenticate", "Bearer realm=\"okta-app-lambda\""),
                "{\"error\":\"unauthorized\",\"message\":\"a valid Okta bearer token is required\"}");
    }

    /** Sends the browser to Okta, remembering where it wanted to go in the state cookie. */
    private Map<String, Object> redirectToOkta(Map<String, Object> event, String path) {
        String state = LambdaUtils.randomToken();
        String rawQuery = event.get("rawQueryString") instanceof String q && !q.isEmpty() ? "?" + q : "";
        String original = LambdaUtils.base64Url((path + rawQuery).getBytes(StandardCharsets.UTF_8));

        String authorizeUrl = this.oktaIssuer + "/v1/authorize"
                + "?client_id=" + LambdaUtils.urlEncode(oktaWebClientId)
                + "&response_type=code"
                + "&scope=" + LambdaUtils.urlEncode(oktaScopes)
                + "&redirect_uri=" + LambdaUtils.urlEncode(LambdaUtils.redirectUri(event))
                + "&state=" + state;

        return LambdaUtils.response(302, Map.of("location", authorizeUrl), "",
                List.of(STATE_COOKIE + "=" + state + "." + original
                        + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=300"));
    }

    /** Exchanges the authorization code for an access token and stores it in the session cookie. */
    private Map<String, Object> callback(Map<String, Object> event, Context context) {
        Map<String, Object> query = LambdaUtils.asMap(event.get("queryStringParameters"));
        if (query.get("error") instanceof String error) {
            return LambdaUtils.htmlError(400, "Okta sign-in failed: " + error + " — "
                    + query.getOrDefault("error_description", ""));
        }

        String code = (String) query.get("code");
        String state = (String) query.get("state");
        String stateCookie = LambdaUtils.cookieValue(event, STATE_COOKIE);
        if (code == null || state == null || stateCookie == null
                || !stateCookie.startsWith(state + ".")) {
            return LambdaUtils.htmlError(400, "Login state mismatch — go back to the site and retry.");
        }
        String original = new String(
                Base64.getUrlDecoder().decode(stateCookie.substring(state.length() + 1)),
                StandardCharsets.UTF_8);

        JsonNode tokens;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(this.oktaIssuer + "/v1/token"))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (oktaWebClientId + ":" + clientSecret()).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "grant_type=authorization_code"
                                    + "&code=" + LambdaUtils.urlEncode(code)
                                    + "&redirect_uri=" + LambdaUtils.urlEncode(LambdaUtils.redirectUri(event))))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            tokens = MAPPER.readTree(response.body());
            if (response.statusCode() != 200) {
                context.getLogger().log("token exchange failed: " + response.body());
                return LambdaUtils.htmlError(502, "Token exchange with Okta failed: "
                        + tokens.path("error_description").asText(tokens.path("error").asText("unknown error")));
            }
        } catch (Exception e) {
            context.getLogger().log("token exchange failed: " + e);
            return LambdaUtils.htmlError(502, "Could not reach Okta to complete sign-in.");
        }

        String accessToken = tokens.path("access_token").asText();
        long maxAge = tokens.path("expires_in").asLong(3600);
        try {
            // Verify before trusting the cookie; also breaks the redirect
            // loop a misconfigured issuer/audience would otherwise cause.
            verifier.decode(accessToken);
        } catch (JwtVerificationException e) {
            context.getLogger().log("token from Okta failed verification: " + e.getMessage());
            return LambdaUtils.htmlError(500, "Okta issued a token this service could not verify — "
                    + "check that OKTA_ISSUER and OKTA_AUDIENCE match the authorization server.");
        }

        return LambdaUtils.response(302, Map.of("location", original.isEmpty() ? "/" : original), "",
                List.of(TOKEN_COOKIE + "=" + accessToken
                                + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + maxAge,
                        STATE_COOKIE + "=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0"));
    }

    private boolean oidcConfigured() {
        return oktaWebClientId != null && !oktaWebClientId.isEmpty()
                && oktaWebClientSecret != null && !oktaWebClientSecret.isEmpty()
                && oktaScopes != null && !oktaScopes.isEmpty();
    }

    /**
     * The web app's client secret, from SSM Parameter Store via the AWS
     * Parameters and Secrets Lambda Extension (localhost HTTP, no SDK).
     * The extension caches reads (default TTL 5 min), so a rotated secret
     * propagates without a redeploy. It only serves requests during
     * invocations — do not call this from static initialization.
     */
    private String clientSecret() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:2773/systemsmanager/parameters/get"
                                + "?withDecryption=true&name=" + LambdaUtils.urlEncode(oktaWebClientSecret)))
                .header("X-Aws-Parameters-Secrets-Token", System.getenv("AWS_SESSION_TOKEN"))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("reading " + oktaWebClientSecret
                    + " from parameter store failed: HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body()).path("Parameter").path("Value").asText();
    }



}
