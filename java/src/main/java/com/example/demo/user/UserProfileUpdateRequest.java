package com.example.demo.user;

import jakarta.validation.constraints.NotBlank;

public record UserProfileUpdateRequest(
        @NotBlank
        String name
) {
}
