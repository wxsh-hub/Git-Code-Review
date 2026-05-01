package com.devops.ai.infrastructure.exception;

public class GitLabApiException extends RuntimeException {

    private final int statusCode;

    public GitLabApiException(String message) {
        super(message);
        this.statusCode = 500;
    }

    public GitLabApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GitLabApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
