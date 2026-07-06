package com.example.oktaapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class JsonUtils {


    private static final ObjectMapper objectMapper = new ObjectMapper();


    public static <T>  T getNestedField(Map<String, Object> objectMap, String... path) {
        try {
            for (int i = 0; i < path.length - 1; i++) {
                objectMap = (Map<String, Object>) objectMap.get(path[i]);
            }
            return (T) objectMap.get(path[path.length - 1]);
        } catch (ClassCastException | NullPointerException e) {
            return null; //key not available on objectMap
        }
    }

    public static <T> T getNestedField(String jsonString, String... path) {
        try {
            Map<String, Object> objectMap = objectMapper.readValue(jsonString, new TypeReference<Map<String, Object>>() {});
            return getNestedField(objectMap, path);
        } catch (Exception e) {
            return null;
        }
    }

}
