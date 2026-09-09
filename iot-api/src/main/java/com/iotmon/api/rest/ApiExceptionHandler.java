package com.iotmon.api.rest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 例外 → 回應的唯一轉譯點。錯誤形狀全站一致：{@code {"message": "..."}}。
 * 領域規則拋 IllegalArgumentException 就是 400；@Repository 內拋的會被 Spring 包一層，訊息在最內層。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, InvalidDataAccessApiUsageException.class,
            DateTimeParseException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, rootMessage(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> badParam(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "參數 " + e.getName() + " 格式不正確：" + e.getValue());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> conflict(DuplicateKeyException e) {
        return body(HttpStatus.CONFLICT, "已存在相同代號的資料");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return body(e.status(), e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message == null ? "請求無效" : message));
    }

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
