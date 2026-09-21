package com.analyzercoder.application.branch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Immutable, account-bound read coordinates. Never a mutable global branch switch. */
public record BranchReadContext(
        UUID contextId,
        UUID repositoryId,
        UUID branchId,
        String branchName,
        UUID contentVersion,
        String commitSha,
        @JsonIgnore Path contentPath,
        Instant expiresAt) {}
