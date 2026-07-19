package com.analyzercoder.domain.codegraph;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import java.util.Optional;

public interface CodeGraphPort {

    Optional<CodeSymbol> getSymbol(CodeRepositoryId repositoryId, String symbolId);

    List<CodeSymbol> searchSymbols(CodeRepositoryId repositoryId, String query, int limit);

    List<CodeSymbol> getFileSymbols(CodeRepositoryId repositoryId, String filePath);

    Optional<CodeSource> getSymbolSource(CodeRepositoryId repositoryId, String symbolId);

    List<CodeGraphEdge> getCallers(CodeRepositoryId repositoryId, String symbolId, int depth);

    List<CodeGraphEdge> getCallees(CodeRepositoryId repositoryId, String symbolId, int depth);

    List<CodeSymbol> getRelatedSymbols(CodeRepositoryId repositoryId, String symbolId, int depth);
}

