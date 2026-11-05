package com.fancy.rideagenthub.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Result {
    private int code;
    private String message;
    private Object data;
    private Long total;

    public static Result ok() {
        return new Result(200, "success", null, 0L);
    }

    public static Result ok(Object data) {
        return new Result(200, "success", data, 0L);
    }

    public static Result ok(Object data, Long total) {
        return new Result(200, "success", data, total);
    }

    public static Result fail(int code, String message) {
        return new Result(code, message, null, 0L);
    }

    public static Result fail(int code, String message, Object data) {
        return new Result(code, message, data, 0L);
    }
}
