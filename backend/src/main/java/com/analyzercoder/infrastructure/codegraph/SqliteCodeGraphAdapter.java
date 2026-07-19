package com.analyzercoder.infrastructure.codegraph;

import com.analyzercoder.domain.codegraph.CodeGraphEdge;
import com.analyzercoder.domain.codegraph.CodeGraphPort;
import com.analyzercoder.domain.codegraph.CodeSource;
import com.analyzercoder.domain.codegraph.CodeSymbol;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SqliteCodeGraphAdapter implements CodeGraphPort {

    @Override
    public Optional<CodeSymbol> getSymbol(CodeRepositoryId repositoryId, String symbolId) {
        return Optional.empty();
    }

    @Override
    public List<CodeSymbol> searchSymbols(CodeRepositoryId repositoryId, String query, int limit) {
        return List.of();
    }

    @Override
    public List<CodeSymbol> getFileSymbols(CodeRepositoryId repositoryId, String filePath) {
        return List.of();
    }

    @Override
    public Optional<CodeSource> getSymbolSource(CodeRepositoryId repositoryId, String symbolId) {
        return Optional.empty();
    }

    @Override
    public List<CodeGraphEdge> getCallers(CodeRepositoryId repositoryId, String symbolId, int depth) {
        return List.of();
    }

    @Override
    public List<CodeGraphEdge> getCallees(CodeRepositoryId repositoryId, String symbolId, int depth) {
        return List.of();
    }

    @Override
    public List<CodeSymbol> getRelatedSymbols(CodeRepositoryId repositoryId, String symbolId, int depth) {
        return List.of();
    }
}

