package com.example.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;

import java.util.LinkedHashMap;
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
 *    cookie. Requires OKTA_WEB_CLIENT_ID of a "Web Application" Okta app
 *    whose sign-in redirect URI is https://<function-url>/callback,
 *    OKTA_WEB_CLIENT_SECRET_PARAM naming the SSM parameter holding its
 *    client secret, and OKTA_SCOPES with the space-separated scopes to
 *    request.
 *
 * OKTA_ISSUER must be a custom authorization server (e.g.
 * https://org.okta.com/oauth2/default) — org authorization server
 * tokens are opaque and cannot be verified locally.
 */
public class OktaAppLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper(); //todo move out
    private static final String TOKEN_COOKIE = "okta_token"; //todo move out

    private final OktaDelegate oktaDelegate;

    public OktaAppLambda() {
        String oktaIssuer = System.getenv("OKTA_ISSUER");
        String oktaAudience = System.getenv("OKTA_AUDIENCE");
        String oktaWebClientId = System.getenv("OKTA_WEB_CLIENT_ID");
        String oktaWebClientSecret = System.getenv("OKTA_WEB_CLIENT_SECRET_PARAM"); //todo investigate PARAM suffix
        String oktaScopes = System.getenv("OKTA_SCOPES");
        this.oktaDelegate = new OktaDelegate(oktaIssuer, oktaAudience, oktaWebClientId, oktaWebClientSecret, oktaScopes);
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Jwt jwt;
        try {
            String token = LambdaUtils.bearerToken(event);
            if (token == null) {
                token = LambdaUtils.cookieValue(event, TOKEN_COOKIE);
            }
            if (token == null) {
                throw new JwtVerificationException("missing bearer token");
            }
            jwt = oktaDelegate.decode(token);
        } catch (JwtVerificationException e) {
            context.getLogger().log("rejected request: " + e.getMessage());
            return oktaDelegate.unauthenticated(event, context);
        }

        Map<String, Object> echo = new LinkedHashMap<>();

        Map<String, Object> requestContext = LambdaUtils.asMap(event.get("requestContext"));
        Map<String, Object> http = LambdaUtils.asMap(requestContext.get("http"));

        echo.put("method", http.get("method"));
        echo.put("path", http.get("path"));
        echo.put("sourceIp", http.get("sourceIp"));
        echo.put("userAgent", http.get("userAgent"));
        echo.put("queryStringParameters", event.get("queryStringParameters"));
        echo.put("headers", LambdaUtils.redactAuthorization(LambdaUtils.asMap(event.get("headers"))));
        echo.put("body", LambdaUtils.decodeBody(event));
        echo.put("requestId", context.getAwsRequestId());
        echo.put("caller", LambdaUtils.callerInfo(jwt.getClaims()));

        try {
            return LambdaUtils.response(200, Map.of("content-type", "application/json"),
                    MAPPER.writeValueAsString(echo));
        } catch (Exception e) {
            return LambdaUtils.response(500, Map.of("content-type", "application/json"),
                    "{\"error\":\"failed to serialize echo response\"}");
        }
    }

}
