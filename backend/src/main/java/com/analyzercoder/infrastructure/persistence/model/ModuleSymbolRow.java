package com.analyzercoder.infrastructure.persistence.model;

/** 承载模块内可用于调用图下钻的轻量符号记录。 */
public record ModuleSymbolRow(
        String symbolName,
        String symbolKind,
        String filePath,
        Integer startLine,
        Integer endLine,
        String language) {}
