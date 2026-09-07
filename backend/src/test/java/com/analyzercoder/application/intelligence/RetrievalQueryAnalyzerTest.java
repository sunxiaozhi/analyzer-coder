package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetrievalQueryAnalyzerTest {
    private final RetrievalQueryAnalyzer analyzer = new RetrievalQueryAnalyzer();

    @Test
    void expandsChineseQuestionsIntoSearchableTerms() {
        RetrievalQueryAnalyzer.Query query = analyzer.analyze("知识问答如何应用模型？");

        assertFalse(query.terms().isEmpty());
        assertTrue(query.terms().stream().anyMatch(term -> term.contains("知识")));
        assertTrue(query.terms().stream().anyMatch(term -> term.contains("模型")));
    }

    @Test
    void removesBlankAndSingleCharacterNoise() {
        RetrievalQueryAnalyzer.Query query = analyzer.analyze(" a / x ");

        assertTrue(query.terms().isEmpty());
    }
    @Test
    void extractsAnExplicitSymbolNextToChineseWithoutSpaces() {
        var query = analyzer.analyze("OrderCheckoutWorkflow在哪里定义？");
        assertTrue(query.terms().contains("ordercheckoutworkflow"));
        assertFalse(query.terms().contains("ordercheckoutworkflow在哪里定义"));
    }
}
