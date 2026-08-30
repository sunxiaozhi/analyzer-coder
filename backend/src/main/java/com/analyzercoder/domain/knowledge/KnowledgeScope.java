package com.analyzercoder.domain.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** 描述工程知识在当前仓库内适用的路径、符号和模块范围。 */
public record KnowledgeScope(
        List<String> pathPatterns, List<String> symbols, List<String> modules) {
    public KnowledgeScope {
        pathPatterns = immutable(pathPatterns);
        symbols = immutable(symbols);
        modules = immutable(modules);
    }

    public static KnowledgeScope empty() {
        return new KnowledgeScope(List.of(), List.of(), List.of());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return pathPatterns.isEmpty() && symbols.isEmpty() && modules.isEmpty();
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
