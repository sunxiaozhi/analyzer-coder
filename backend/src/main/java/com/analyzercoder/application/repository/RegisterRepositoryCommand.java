package com.analyzercoder.application.repository;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** 封装发起代码仓库用例所需的输入参数，作为稳定的应用层调用边界。 */
public record RegisterRepositoryCommand(
        @NotBlank String name, @NotBlank String path, UUID ownerAccountId) {
    public RegisterRepositoryCommand(String name, String path) {
        this(name, path, null);
    }
}
