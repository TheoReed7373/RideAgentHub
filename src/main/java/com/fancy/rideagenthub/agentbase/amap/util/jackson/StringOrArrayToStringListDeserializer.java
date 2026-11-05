package com.fancy.rideagenthub.agentbase.amap.util.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 兼容高德 API 某些字段可能返回：
 * - 字符串："xxx"（单个值）
 * - 数组：["a","b"]
 * - 空数组：[]
 * - null
 */
public class StringOrArrayToStringListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        if (token == JsonToken.VALUE_STRING) {
            String value = p.getValueAsString();
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return Collections.emptyList();
            }
            return List.of(trimmed);
        }

        if (token == JsonToken.START_ARRAY) {
            List<String> result = new ArrayList<>();
            while ((token = p.nextToken()) != JsonToken.END_ARRAY) {
                if (token == JsonToken.VALUE_NULL) {
                    continue;
                }
                String value = p.getValueAsString();
                if (value == null) {
                    continue;
                }
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }

        throw JsonMappingException.from(p,
                "Expected string or array for List<String>, but got token: " + token);
    }
}
