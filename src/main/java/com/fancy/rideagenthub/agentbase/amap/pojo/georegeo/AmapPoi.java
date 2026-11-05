package com.fancy.rideagenthub.agentbase.amap.pojo.georegeo;

import com.fancy.rideagenthub.agentbase.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; /**
 * POI 信息 (extensions=all)
 */
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapPoi(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String id,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String name,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String type,
                Object tel,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String direction,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String address,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String location,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String businessarea
) {
        public String getTelString() {
                if (tel instanceof String s) {
                        return s;
                }
                return "";
        }
}
