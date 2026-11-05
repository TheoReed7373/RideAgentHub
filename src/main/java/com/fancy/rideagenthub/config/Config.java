package com.fancy.rideagenthub.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

@Configuration
public class Config {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            // BigDecimal 依然全局转 String
            builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);

            // Long 类型智能转换
            builder.serializerByType(Long.class, new JsonSerializer<Long>() {
                @Override
                public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    if (value == null) {
                        gen.writeNull();
                        return;
                    }
                    // JS 最大安全整数是 9007199254740991
                    // 只要超过这个范围（或者小于负的这个范围），就转 String
                    // 雪花ID通常是 19位，远超这个范围，会自动转 String
                    if (value > 9007199254740991L || value < -9007199254740991L) {
                        gen.writeString(value.toString());
                    } else {
                        // 比如时间戳、状态码、数量等安全范围内的数字，依然输出为 Number
                        gen.writeNumber(value);
                    }
                }
            });
        };
    }
}
