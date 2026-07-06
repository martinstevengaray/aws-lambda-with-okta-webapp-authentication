package com.example.oktaapp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LambdaUtils {

    //html util
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

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

}
