package com.example.demo.user;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,
        String phone,
        String name,
        Instant createdAt
) {
}
