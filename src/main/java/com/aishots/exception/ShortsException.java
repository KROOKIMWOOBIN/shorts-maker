package com.aishots.exception;

/**
 * 비즈니스 로직 예외 — 클라이언트에 안전하게 노출 가능한 메시지만 담습니다.
 * 내부 시스템 정보(경로, 스택트레이스)는 절대 포함하지 않습니다.
 */
public class ShortsException extends RuntimeException {

    private final String userMessage; // 클라이언트에 보낼 안전한 메시지

    public ShortsException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    public ShortsException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
