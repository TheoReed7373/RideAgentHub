package com.fancy.rideagenthub.exception;

import com.fancy.rideagenthub.domain.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result> handleUnauthorized(UnauthorizedException e) { return response(401, e.getMessage()); }
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result> handleForbidden(ForbiddenException e) { return response(403, e.getMessage()); }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) { return response(e.getCode(), e.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException e) { return response(400, e.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result> handleInvalidRequest(Exception e) { return response(400, "请求参数格式不正确"); }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Result> handleDuplicate(org.springframework.dao.DuplicateKeyException e) {
        return response(409, "记录已存在或请求已被处理");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleException(Exception e) {
        log.error("Unexpected error", e);
        return response(500, "服务器内部错误");
    }
    private ResponseEntity<Result> response(int code, String message) {
        int status = code >= 400 && code <= 599 ? code : 400;
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}
