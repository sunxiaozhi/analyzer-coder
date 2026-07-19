package com.analyzercoder.domain.codegraph;

public record CodeGraphEdge(
    CodeSymbol from,
    CodeSymbol to,
    String relation,
    int depth
) {
}

