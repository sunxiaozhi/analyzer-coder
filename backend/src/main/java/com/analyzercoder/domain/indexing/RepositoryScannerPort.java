package com.analyzercoder.domain.indexing;

import com.analyzercoder.domain.repository.CodeRepository;
import java.util.List;

public interface RepositoryScannerPort {

    List<ScannedRepositoryFile> scan(CodeRepository repository);
}
