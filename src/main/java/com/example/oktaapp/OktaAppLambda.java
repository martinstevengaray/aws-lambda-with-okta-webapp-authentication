package com.example.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerifiers;
import com.okta.jwt.JwtVerificationException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Echoes back selected contents of a Lambda Function URL request
 * (API Gateway v2 HTTP event format). Two ways in:
 *
 *  - API clients send an Okta-issued bearer token; requests without
 *    a valid one get a 401.
 *  - Browsers (Accept: text/html) without a token are sent through the
 *    OIDC authorization-code flow: redirect to Okta, exchange the code
 *    at /callback, and store the access token in an HttpOnly session
 *    cookie. Requires OKTA_CLIENT_ID / OKTA_CLIENT_SECRET of a
 *    "Web Application" Okta app whose sign-in redirect URI is
 *    https://<function-url>/callback.
 *
 * OKTA_ISSUER must be a custom authorization server (e.g.
 * https://org.okta.com/oauth2/default) — org authorization server
 * tokens are opaque and cannot be verified locally.
 */
public class OktaAppLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ISSUER = System.getenv("OKTA_ISSUER");
    private static final String CLIENT_ID = System.getenv("OKTA_CLIENT_ID");
    private static final String CLIENT_SECRET = System.getenv("OKTA_CLIENT_SECRET");
    private static final String SCOPES = "okta-lambda-client-scope";

    private static final String TOKEN_COOKIE = "okta_token";
    private static final String STATE_COOKIE = "oauth_state";
    private static final String CALLBACK_PATH = "/callback";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AccessTokenVerifier verifier = JwtVerifiers.accessTokenVerifierBuilder()
            .setIssuer(ISSUER)
            .setAudience(System.getenv("OKTA_AUDIENCE"))
            .setConnectionTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Jwt jwt;
        try {
            String token = bearerToken(event);
            if (token == null) {
                token = cookieValue(event, TOKEN_COOKIE);
            }
            if (token == null) {
                throw new JwtVerificationException("missing bearer token");
            }
            jwt = verifier.decode(token);
        } catch (JwtVerificationException e) {
            context.getLogger().log("rejected request: " + e.getMessage());
            return unauthenticated(event, context);
        }

        Map<String, Object> echo = new LinkedHashMap<>();

        Map<String, Object> requestContext = asMap(event.get("requestContext"));
        Map<String, Object> http = asMap(requestContext.get("http"));

        echo.put("method", http.get("method"));
        echo.put("path", http.get("path"));
        echo.put("sourceIp", http.get("sourceIp"));
        echo.put("userAgent", http.get("userAgent"));
        echo.put("queryStringParameters", event.get("queryStringParameters"));
        echo.put("headers", redactAuthorization(asMap(event.get("headers"))));
        echo.put("body", decodeBody(event));
        echo.put("requestId", context.getAwsRequestId());
        echo.put("caller", callerInfo(jwt.getClaims()));

        try {
            return response(200, Map.of("content-type", "application/json"),
                    MAPPER.writeValueAsString(echo));
        } catch (Exception e) {
            return response(500, Map.of("content-type", "application/json"),
                    "{\"error\":\"failed to serialize echo response\"}");
        }
    }

    /**
     * No valid token: finish the OIDC flow on /callback, start it for
     * browsers, and keep the plain 401 for API clients.
     */
    private Map<String, Object> unauthenticated(Map<String, Object> event, Context context) {
        String path = (String) asMap(asMap(event.get("requestContext")).get("http")).get("path");

        if (CALLBACK_PATH.equals(path)) {
            return callback(event, context);
        }
        if (acceptsHtml(event) && oidcConfigured()) {
            return redirectToOkta(event, path);
        }
        return response(401,
                Map.of("content-type", "application/json",
                        "www-authenticate", "Bearer realm=\"okta-app-lambda\""),
                "{\"error\":\"unauthorized\",\"message\":\"a valid Okta bearer token is required\"}");
    }

    /** Sends the browser to Okta, remembering where it wanted to go in the state cookie. */
    private static Map<String, Object> redirectToOkta(Map<String, Object> event, String path) {
        String state = randomToken();
        String rawQuery = event.get("rawQueryString") instanceof String q && !q.isEmpty() ? "?" + q : "";
        String original = base64Url((path + rawQuery).getBytes(StandardCharsets.UTF_8));

        String authorizeUrl = ISSUER + "/v1/authorize"
                + "?client_id=" + urlEncode(CLIENT_ID)
                + "&response_type=code"
                + "&scope=" + urlEncode(SCOPES)
                + "&redirect_uri=" + urlEncode(redirectUri(event))
                + "&state=" + state;

        return response(302, Map.of("location", authorizeUrl), "",
                List.of(STATE_COOKIE + "=" + state + "." + original
                        + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=300"));
    }

    /** Exchanges the authorization code for an access token and stores it in the session cookie. */
    private Map<String, Object> callback(Map<String, Object> event, Context context) {
        Map<String, Object> query = asMap(event.get("queryStringParameters"));
        if (query.get("error") instanceof String error) {
            return htmlError(400, "Okta sign-in failed: " + error + " — "
                    + query.getOrDefault("error_description", ""));
        }

        String code = (String) query.get("code");
        String state = (String) query.get("state");
        String stateCookie = cookieValue(event, STATE_COOKIE);
        if (code == null || state == null || stateCookie == null
                || !stateCookie.startsWith(state + ".")) {
            return htmlError(400, "Login state mismatch — go back to the site and retry.");
        }
        String original = new String(
                Base64.getUrlDecoder().decode(stateCookie.substring(state.length() + 1)),
                StandardCharsets.UTF_8);

        JsonNode tokens;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ISSUER + "/v1/token"))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "grant_type=authorization_code"
                                    + "&code=" + urlEncode(code)
                                    + "&redirect_uri=" + urlEncode(redirectUri(event))))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            tokens = MAPPER.readTree(response.body());
            if (response.statusCode() != 200) {
                context.getLogger().log("token exchange failed: " + response.body());
                return htmlError(502, "Token exchange with Okta failed: "
                        + tokens.path("error_description").asText(tokens.path("error").asText("unknown error")));
            }
        } catch (Exception e) {
            context.getLogger().log("token exchange failed: " + e);
            return htmlError(502, "Could not reach Okta to complete sign-in.");
        }

        String accessToken = tokens.path("access_token").asText();
        long maxAge = tokens.path("expires_in").asLong(3600);
        try {
            // Verify before trusting the cookie; also breaks the redirect
            // loop a misconfigured issuer/audience would otherwise cause.
            verifier.decode(accessToken);
        } catch (JwtVerificationException e) {
            context.getLogger().log("token from Okta failed verification: " + e.getMessage());
            return htmlError(500, "Okta issued a token this service could not verify — "
                    + "check that OKTA_ISSUER and OKTA_AUDIENCE match the authorization server.");
        }

        return response(302, Map.of("location", original.isEmpty() ? "/" : original), "",
                List.of(TOKEN_COOKIE + "=" + accessToken
                                + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + maxAge,
                        STATE_COOKIE + "=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0"));
    }

    private static boolean oidcConfigured() {
        return CLIENT_ID != null && !CLIENT_ID.isEmpty()
                && CLIENT_SECRET != null && !CLIENT_SECRET.isEmpty();
    }

    private static String redirectUri(Map<String, Object> event) {
        String domain = (String) asMap(event.get("requestContext")).get("domainName");
        return "https://" + domain + CALLBACK_PATH;
    }

    private static boolean acceptsHtml(Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : asMap(event.get("headers")).entrySet()) {
            if ("accept".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof String s) {
                return s.contains("text/html");
            }
        }
        return false;
    }

    private static String bearerToken(Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : asMap(event.get("headers")).entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() instanceof String s
                    && s.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return s.substring(7).trim();
            }
        }
        return null;
    }

    private static String cookieValue(Map<String, Object> event, String name) {
        if (event.get("cookies") instanceof List<?> cookies) {
            for (Object cookie : cookies) {
                if (cookie instanceof String s && s.startsWith(name + "=")) {
                    return s.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    private static Map<String, Object> callerInfo(Map<String, Object> claims) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("sub", claims.get("sub"));
        if (claims.containsKey("cid")) {
            caller.put("cid", claims.get("cid"));
        }
        return caller;
    }

    private static Map<String, Object> redactAuthorization(Map<String, Object> headers) {
        Map<String, Object> redacted = new LinkedHashMap<>(headers);
        redacted.replaceAll((name, value) ->
                "authorization".equalsIgnoreCase(name) ? "<redacted>" : value);
        return redacted;
    }

    private static Map<String, Object> htmlError(int statusCode, String message) {
        return response(statusCode, Map.of("content-type", "text/html; charset=utf-8"),
                "<!DOCTYPE html><html><body><h1>Sign-in problem</h1><p>"
                        + message + "</p><p><a href=\"/\">Try again</a></p></body></html>");
    }

    private static Map<String, Object> response(int statusCode, Map<String, String> headers, String body) {
        return response(statusCode, headers, body, null);
    }

    private static Map<String, Object> response(int statusCode, Map<String, String> headers,
                                                String body, List<String> cookies) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", headers);
        response.put("body", body);
        if (cookies != null) {
            response.put("cookies", cookies);
        }
        return response;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Object decodeBody(Map<String, Object> event) {
        Object body = event.get("body");
        if (body instanceof String s && Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
