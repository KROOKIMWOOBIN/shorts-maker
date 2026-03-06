package com.aishots.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전역 예외 핸들러
 *
 * [보안] 내부 예외 메시지(경로, 라이브러리 정보 등)를 클라이언트에 절대 노출하지 않습니다.
 * - ShortsException: 사전에 정의한 안전한 메시지만 반환
 * - 그 외 모든 예외: 고정된 일반 메시지만 반환, 상세는 서버 로그에만 기록
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a)); // 동일 필드 중복 시 첫 번째 유지

        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "입력값을 확인해주세요.",
                "errors", fieldErrors
        ));
    }

    // 비즈니스 예외 — 안전한 메시지 노출 허용
    @ExceptionHandler(ShortsException.class)
    public ResponseEntity<Map<String, Object>> handleShortsException(ShortsException ex) {
        log.warn("비즈니스 예외 발생: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ex.getUserMessage()
        ));
    }

    // 그 외 모든 예외 — 내부 정보 절대 노출 금지
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        // 상세 로그는 서버에만 기록
        log.error("예기치 않은 오류 발생", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false,
                "message", "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        ));
    }
}
