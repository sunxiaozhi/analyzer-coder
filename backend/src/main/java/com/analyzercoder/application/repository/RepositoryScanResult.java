package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;

public record RepositoryScanResult(boolean changed, CodeRepository repository) {
}
