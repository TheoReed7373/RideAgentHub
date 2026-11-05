package com.fancy.rideagenthub.agentbase.amap.util.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 兼容高德 API 某些字段偶发返回空数组 []（而不是空字符串/缺失）的情况。
 * <p>
 * 规则：
 * <ul>
 *   <li>字符串：原样返回</li>
 *   <li>空数组：返回 null</li>
 *   <li>非空数组：按逗号拼接为字符串</li>
 *   <li>其它类型：尽量转为文本</li>
 * </ul>
 */
public class StringOrEmptyArrayToNullDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                if (child != null && !child.isNull()) {
                    String text = child.asText();
                    if (text != null && !text.isBlank()) {
                        parts.add(text);
                    }
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            return String.join(",", parts);
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }
}
