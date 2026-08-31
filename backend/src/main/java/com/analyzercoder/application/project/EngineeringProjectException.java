package com.analyzercoder.application.project;

public class EngineeringProjectException extends RuntimeException {
    private final String code;

    public EngineeringProjectException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
