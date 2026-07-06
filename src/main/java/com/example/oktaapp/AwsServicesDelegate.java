package com.example.oktaapp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AwsServicesDelegate {

    private static final ObjectMapper MAPPER = new ObjectMapper(); //todo move out

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();


    public static String fetchSmmParameterValue(String ssmParameterKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:2773/systemsmanager/parameters/get"
                                + "?withDecryption=true&name=" + HttpUtils.urlEncode(ssmParameterKey)))
                .header("X-Aws-Parameters-Secrets-Token", System.getenv("AWS_SESSION_TOKEN"))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("reading " + ssmParameterKey
                    + " from parameter store failed: HTTP " + response.statusCode());
        }
        return JsonUtils.getNestedField(response.body(), "Parameter", "Value");
    }

}
