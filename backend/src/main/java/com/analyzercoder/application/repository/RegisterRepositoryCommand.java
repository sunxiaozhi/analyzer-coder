package com.analyzercoder.application.repository;

import jakarta.validation.constraints.NotBlank;

public record RegisterRepositoryCommand(
    @NotBlank String name,
    @NotBlank String path
) {
}

