package com.analyzercoder.domain.codegraph;

/** 描述代码来源的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record CodeSource(CodeSymbol symbol, String content) {}
