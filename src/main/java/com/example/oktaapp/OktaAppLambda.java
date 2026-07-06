package com.example.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class OktaAppLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final OktaDelegate oktaDelegate;

    public OktaAppLambda() throws Exception {
        String oktaIssuer = System.getenv("OKTA_ISSUER");
        String oktaAudience = System.getenv("OKTA_AUDIENCE");
        String oktaWebClientId = System.getenv("OKTA_WEB_CLIENT_ID");
        String oktaScopes = System.getenv("OKTA_SCOPES");
        String oktaWebClientSecretSsmParameterKey = System.getenv("OKTA_WEB_CLIENT_SECRET_SSM_PARAMETER_KEY");
        String oktaWebClientSecret = AwsServicesDelegate.fetchSmmParameterValue(oktaWebClientSecretSsmParameterKey);
        this.oktaDelegate = new OktaDelegate(oktaIssuer, oktaAudience, oktaWebClientId, oktaWebClientSecret, oktaScopes);
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Jwt jwt;
        try {
            jwt = oktaDelegate.readJwt(event);
        } catch (JwtVerificationException e) {
            return oktaDelegate.unauthenticated(event, context);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> requestContext = LambdaUtils.asMap(event.get("requestContext"));
        Map<String, Object> http = LambdaUtils.asMap(requestContext.get("http"));
        response.put("method", http.get("method"));
        response.put("path", http.get("path"));
        response.put("sourceIp", http.get("sourceIp"));
        response.put("userAgent", http.get("userAgent"));
        response.put("queryStringParameters", event.get("queryStringParameters"));
        response.put("headers", redactAuthorization(LambdaUtils.asMap(event.get("headers"))));
        response.put("body", decodeBody(event));
        response.put("requestId", context.getAwsRequestId());
        response.put("caller", callerInfo(jwt.getClaims()));

        return LambdaUtils.response(200, Map.of("content-type", "application/json"),
                JsonUtils.toString(response));
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
        redacted.replaceAll((name, value) -> "authorization".equalsIgnoreCase(name) ? "<redacted>" : value);
        return redacted;
    }

    private static Object decodeBody(Map<String, Object> event) {
        Object body = event.get("body");
        if (body instanceof String s && Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        }
        return body;
    }

}
