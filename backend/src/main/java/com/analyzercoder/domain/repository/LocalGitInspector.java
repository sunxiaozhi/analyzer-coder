package com.analyzercoder.domain.repository;

import java.nio.file.Path;

public interface LocalGitInspector {
    GitRepositorySnapshot inspect(Path repositoryRoot);
}
