package com.scoresync.web;

/**
 * 全局异常处理器
 * 统一处理各类业务异常，返回标准化的 API 错误响应
 */

import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.MediaUnavailableException;
import com.scoresync.support.RenderJobConflictException;
import com.scoresync.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 处理资源不存在异常（404）
     */
    @ExceptionHandler(DomainNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(DomainNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", ex.getMessage(), null));
    }

    /**
     * 处理业务校验异常（400）
     */
    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ApiError> handleValidation(DomainValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("DOMAIN_VALIDATION", ex.getMessage(), Collections.emptyMap()));
    }

    /**
     * 处理媒体文件不可用异常（416）
     */
    @ExceptionHandler(MediaUnavailableException.class)
    public ResponseEntity<ApiError> handleMediaUnavailable(MediaUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .body(new ApiError("MEDIA_UNAVAILABLE", ex.getMessage(), Collections.emptyMap()));
    }

    /**
     * 处理渲染任务冲突异常（409）
     */
    @ExceptionHandler(RenderJobConflictException.class)
    public ResponseEntity<ApiError> handleRenderJobConflict(RenderJobConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.getCode(), ex.getMessage(), Collections.emptyMap()));
    }

    /**
     * 处理请求参数校验异常（400）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("DOMAIN_VALIDATION", "Validation failed", fields));
    }

    /**
     * 处理请求体解析异常（400）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("DOMAIN_VALIDATION", "Malformed request body", null));
    }
}
