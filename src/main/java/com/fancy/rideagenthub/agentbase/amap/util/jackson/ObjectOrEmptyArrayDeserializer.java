package com.fancy.rideagenthub.agentbase.amap.util.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;

/**
 * 通用反序列化器：兼容高德 API 某些字段可能返回
 * <ul>
 *   <li>对象：{...}</li>
 *   <li>空数组：[]</li>
 *   <li>null</li>
 * </ul>
 * 典型字段：biz_ext、indoor_data。
 */
public class ObjectOrEmptyArrayDeserializer extends JsonDeserializer<Object> implements ContextualDeserializer {

    private final JavaType targetType;

    public ObjectOrEmptyArrayDeserializer() {
        this.targetType = null;
    }

    private ObjectOrEmptyArrayDeserializer(JavaType targetType) {
        this.targetType = targetType;
    }

    @Override
    public Object deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        ObjectCodec codec = parser.getCodec();
        JsonNode node = codec.readTree(parser);
        if (node == null || node.isNull()) {
            return null;
        }

        JsonNode effective = node;
        if (node.isArray()) {
            if (node.isEmpty()) {
                return null;
            }
            JsonNode first = node.get(0);
            if (first == null || first.isNull()) {
                return null;
            }
            effective = first;
        }

        if (!effective.isObject()) {
            return null;
        }

        JavaType t = targetType != null ? targetType : ctxt.getContextualType();
        if (t == null) {
            return null;
        }

        if (codec instanceof ObjectMapper mapper) {
            return mapper.convertValue(effective, t);
        }
        // fallback：尽量按 raw class 处理
        return ctxt.readTreeAsValue(effective, t.getRawClass());
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
        JavaType t = property != null ? property.getType() : ctxt.getContextualType();
        return new ObjectOrEmptyArrayDeserializer(t);
    }
}
