package com.analyzercoder.application.repository;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record RegisterRepositoryCommand(@NotBlank String name, @NotBlank String path, UUID ownerAccountId) {
    public RegisterRepositoryCommand(String name, String path) { this(name, path, null); }
}
