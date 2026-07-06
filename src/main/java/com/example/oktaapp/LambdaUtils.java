package com.example.oktaapp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LambdaUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CALLBACK_PATH = "/callback";

    public static String redirectUri(Map<String, Object> event) {
        String domain = (String) asMap(event.get("requestContext")).get("domainName");
        return "https://" + domain + CALLBACK_PATH;
    }

    public static boolean acceptsHtml(Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : asMap(event.get("headers")).entrySet()) {
            if ("accept".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof String s) {
                return s.contains("text/html");
            }
        }
        return false;
    }

    public static String bearerToken(Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : asMap(event.get("headers")).entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() instanceof String s
                    && s.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return s.substring(7).trim();
            }
        }
        return null;
    }

    public static String cookieValue(Map<String, Object> event, String name) {
        if (event.get("cookies") instanceof List<?> cookies) {
            for (Object cookie : cookies) {
                if (cookie instanceof String s && s.startsWith(name + "=")) {
                    return s.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    public static Map<String, Object> callerInfo(Map<String, Object> claims) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("sub", claims.get("sub"));
        if (claims.containsKey("cid")) {
            caller.put("cid", claims.get("cid"));
        }
        return caller;
    }

    public static Map<String, Object> redactAuthorization(Map<String, Object> headers) {
        Map<String, Object> redacted = new LinkedHashMap<>(headers);
        redacted.replaceAll((name, value) ->
                "authorization".equalsIgnoreCase(name) ? "<redacted>" : value);
        return redacted;
    }

    public static Map<String, Object> htmlError(int statusCode, String message) {
        return response(statusCode, Map.of("content-type", "text/html; charset=utf-8"),
                "<!DOCTYPE html><html><body><h1>Sign-in problem</h1><p>"
                        + message + "</p><p><a href=\"/\">Try again</a></p></body></html>");
    }

    public static Map<String, Object> response(int statusCode, Map<String, String> headers, String body) {
        return response(statusCode, headers, body, null);
    }

    public static Map<String, Object> response(int statusCode, Map<String, String> headers,
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

    public static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    public static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static Object decodeBody(Map<String, Object> event) {
        Object body = event.get("body");
        if (body instanceof String s && Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

}
