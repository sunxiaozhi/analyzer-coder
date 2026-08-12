package com.analyzercoder.domain.codegraph;

/** 描述代码图谱的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record CodeGraphEdge(CodeSymbol from, CodeSymbol to, String relation, int depth) {}
