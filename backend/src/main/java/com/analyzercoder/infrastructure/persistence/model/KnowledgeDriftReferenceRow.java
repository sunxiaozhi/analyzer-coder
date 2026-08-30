package com.analyzercoder.infrastructure.persistence.model;

/** 某一知识修订绑定的不可变代码引用事实。 */
public record KnowledgeDriftReferenceRow(
        String filePath,
        String symbolName,
        Integer startLine,
        Integer endLine,
        String contentHash) {}
