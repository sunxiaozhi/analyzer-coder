package com.analyzercoder.domain.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** 描述知识在项目代码中的路径、符号和模块适用范围。 */
public record KnowledgeScope(
        List<String> pathPatterns,
        List<String> symbols,
        List<String> modules) {
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
        return pathPatterns.isEmpty()
                && symbols.isEmpty()
                && modules.isEmpty();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
