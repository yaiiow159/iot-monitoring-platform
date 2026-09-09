package com.iotmon.api.rest;

import org.springframework.http.HttpStatus;

/** 控制器要回特定狀態碼與訊息時丟這個；轉成回應的工作在 {@link ApiExceptionHandler}。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public HttpStatus status() {
        return status;
    }
}
