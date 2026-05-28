package com.example.demo.user;

public class DuplicatePhoneException extends RuntimeException {
    public DuplicatePhoneException(String phone) {
        super("phone already registered: " + phone);
    }
}
