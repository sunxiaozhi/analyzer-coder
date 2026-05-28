package com.example.demo.user;

import java.time.Instant;

public record ApiError(
        String code,
        String message,
        Instant timestamp
) {
}
