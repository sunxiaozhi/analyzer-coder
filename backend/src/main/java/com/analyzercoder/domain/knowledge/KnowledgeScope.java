package com.analyzercoder.domain.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.UUID;

/** 描述工程知识在仓库内及受治理工程项目中的确定性适用范围。 */
public record KnowledgeScope(
        List<String> pathPatterns,
        List<String> symbols,
        List<String> modules,
        List<UUID> repositoryIds,
        List<String> serviceNames,
        List<UUID> contractIds) {
    public KnowledgeScope {
        pathPatterns = immutable(pathPatterns);
        symbols = immutable(symbols);
        modules = immutable(modules);
        repositoryIds = immutable(repositoryIds);
        serviceNames = immutable(serviceNames);
        contractIds = immutable(contractIds);
    }

    /** 兼容 V9 的仓库内三字段 JSON 和调用代码。 */
    public KnowledgeScope(
            List<String> pathPatterns, List<String> symbols, List<String> modules) {
        this(pathPatterns, symbols, modules, List.of(), List.of(), List.of());
    }

    public static KnowledgeScope empty() {
        return new KnowledgeScope(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return pathPatterns.isEmpty()
                && symbols.isEmpty()
                && modules.isEmpty()
                && repositoryIds.isEmpty()
                && serviceNames.isEmpty()
                && contractIds.isEmpty();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
