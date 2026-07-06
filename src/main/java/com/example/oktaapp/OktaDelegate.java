package com.example.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;
import com.okta.jwt.JwtVerifiers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class OktaDelegate {

    private static final String OKTA_TOKEN_COOKIE = "okta_token";
    private static final String OATH_STATE_COOKIE = "oauth_state";
    private static final String CALLBACK_PATH = "/callback";

    private final String oktaIssuer;
    private final String oktaWebClientId;
    private final String oktaWebClientSecret;
    private final String oktaScopes;
    private final AccessTokenVerifier verifier;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom;

    public OktaDelegate(String oktaIssuer,
                        String oktaAudience,
                        String oktaWebClientId,
                        String oktaWebClientSecret,
                        String oktaScopes) {
        this.oktaIssuer = oktaIssuer;
        this.oktaWebClientId = oktaWebClientId;
        this.oktaWebClientSecret = oktaWebClientSecret;
        this.oktaScopes = oktaScopes;
        this.verifier = JwtVerifiers.accessTokenVerifierBuilder()
                .setIssuer(oktaIssuer)
                .setAudience(oktaAudience)
                .setConnectionTimeout(Duration.ofSeconds(5))
                .build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.secureRandom = new SecureRandom();
    }

    public Jwt readJwt(Map<String, Object> event) throws JwtVerificationException {
        String token = readBearerToken(event);
        if (token == null) {
            token = readCookieValue(event, OKTA_TOKEN_COOKIE);
        }
        return verifier.decode(token);
    }

    public Map<String, Object> unauthenticated(Map<String, Object> event, Context context) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
        if (!CALLBACK_PATH.equals(path)) {
            return redirectToOkta(event, path);
        }
        return callback(event, context);
    }

    private String readBearerToken(Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : LambdaUtils.asMap(event.get("headers")).entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() instanceof String s
                    && s.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return s.substring(7).trim();
            }
        }
        return null;
    }

    private String readCookieValue(Map<String, Object> event, String cookieName) {
        if (event.get("cookies") instanceof List<?> cookies) {
            for (Object cookie : cookies) {
                if (cookie instanceof String s && s.startsWith(cookieName + "=")) {
                    return s.substring(cookieName.length() + 1);
                }
            }
        }
        return null;
    }

    // Sends the browser to Okta, remembering where it wanted to go in the state cookie.
    private Map<String, Object> redirectToOkta(Map<String, Object> event, String path) {
        byte[] randomTokenBytes = new byte[24];
        secureRandom.nextBytes(randomTokenBytes);
        String state = base64Url(randomTokenBytes);
        String rawQuery = event.get("rawQueryString") instanceof String q && !q.isEmpty() ? "?" + q : "";
        String original = base64Url((path + rawQuery).getBytes(StandardCharsets.UTF_8));
        String domainName = JsonUtils.getNestedField(event,"requestContext", "domainName");
        String redirectUri = "https://" + domainName + CALLBACK_PATH;
        String authorizeUrl = this.oktaIssuer + "/v1/authorize"
                + "?client_id=" + LambdaUtils.urlEncode(oktaWebClientId)
                + "&response_type=code"
                + "&scope=" + LambdaUtils.urlEncode(oktaScopes)
                + "&redirect_uri=" + LambdaUtils.urlEncode(redirectUri)
                + "&state=" + state;

        return LambdaUtils.response(302, Map.of("location", authorizeUrl), "",
                List.of(OATH_STATE_COOKIE + "=" + state + "." + original
                        + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=300"));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Exchanges the authorization code for an access token and stores it in the session cookie.
    private Map<String, Object> callback(Map<String, Object> event, Context context) {
        final String error = JsonUtils.getNestedField(event, "queryStringParameters", "error");
        if (error != null) {
            String errorDescription = JsonUtils.getNestedField(event, "queryStringParameters", "error_description");
            context.getLogger().log("Okta sign-in failed: " + error + " — " + errorDescription);
            return LambdaUtils.htmlError(400, "Okta sign-in failed");
        }
        final String code = JsonUtils.getNestedField(event, "queryStringParameters", "code");
        final String state = JsonUtils.getNestedField(event, "queryStringParameters", "state");
        final String oathStateCookie = readCookieValue(event, OATH_STATE_COOKIE);
        if (code == null || state == null || oathStateCookie == null || !oathStateCookie.startsWith(state + ".")) {
            return LambdaUtils.htmlError(400, "Login state mismatch, retry.");
        }
        //verify "code" in queryStringParameters to retrieve an accessToken for client
        final String domainName = JsonUtils.getNestedField(event,"requestContext", "domainName");
        final String redirectUri = "https://" + domainName + CALLBACK_PATH;
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(oktaIssuer + "/v1/token"))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (oktaWebClientId + ":" + oktaWebClientSecret).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "grant_type=authorization_code"
                                    + "&code=" + LambdaUtils.urlEncode(code)
                                    + "&redirect_uri=" + LambdaUtils.urlEncode(redirectUri)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                context.getLogger().log("token exchange failed: " + response.body());
                return LambdaUtils.htmlError(502, "Token exchange with Okta failed");
            }
        } catch (Exception e) {
            context.getLogger().log("token exchange failed: " + e);
            return LambdaUtils.htmlError(502, "Could not reach Okta to complete sign-in.");
        }
        String accessToken = JsonUtils.getNestedField(response.body(), "access_token");
        try {
            verifier.decode(accessToken); // Verify once before trusting the cookie to avoid a redirect loop
        } catch (JwtVerificationException e) {
            context.getLogger().log("token from Okta failed verification: " + e.getMessage());
            return LambdaUtils.htmlError(500, "Okta issued a token this service could not verify.");
        }
        String originallyRequestedUrl = new String(
                Base64.getUrlDecoder().decode(oathStateCookie.substring(state.length() + 1)),
                StandardCharsets.UTF_8);
        Integer maxAge =  JsonUtils.getNestedField(response.body(), "expires_in");
        return LambdaUtils.response(302, Map.of("location", originallyRequestedUrl), "", List.of(
                OKTA_TOKEN_COOKIE + "=" + accessToken + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + maxAge,
                OATH_STATE_COOKIE + "=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0")); //clear out oath cookie
    }

}
