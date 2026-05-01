package com.devops.ai.infrastructure.exception;

public class DocumentGenerateException extends RuntimeException {

    public DocumentGenerateException(String message) {
        super(message);
    }

    public DocumentGenerateException(String message, Throwable cause) {
        super(message, cause);
    }
}
