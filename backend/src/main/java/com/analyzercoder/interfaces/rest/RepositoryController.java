package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RegisterRepositoryCommand;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final RegisterRepositoryUseCase registerRepositoryUseCase;

    public RepositoryController(RegisterRepositoryUseCase registerRepositoryUseCase) {
        this.registerRepositoryUseCase = registerRepositoryUseCase;
    }

    @PostMapping
    public RepositoryResponse register(@Valid @RequestBody RegisterRepositoryRequest request) {
        CodeRepository repository = registerRepositoryUseCase.register(
            new RegisterRepositoryCommand(request.name(), request.path())
        );
        return RepositoryResponse.from(repository);
    }

    @GetMapping("/{repositoryId}")
    public RepositoryResponse get(@PathVariable UUID repositoryId) {
        CodeRepository repository = registerRepositoryUseCase.get(CodeRepositoryId.of(repositoryId));
        return RepositoryResponse.from(repository);
    }

    @GetMapping
    public List<RepositoryResponse> list() {
        return registerRepositoryUseCase.list().stream()
            .map(RepositoryResponse::from)
            .toList();
    }

    @DeleteMapping("/{repositoryId}")
    public void delete(@PathVariable UUID repositoryId) {
        registerRepositoryUseCase.delete(CodeRepositoryId.of(repositoryId));
    }

    public record RegisterRepositoryRequest(
        @NotBlank String name,
        @NotBlank String path
    ) {
    }

    public record RepositoryResponse(
        UUID id,
        String name,
        String path,
        String codeGraphPath
    ) {

        public static RepositoryResponse from(CodeRepository repository) {
            return new RepositoryResponse(
                repository.id().value(),
                repository.name(),
                repository.path().toString(),
                repository.codeGraphPath().toString()
            );
        }
    }
}

