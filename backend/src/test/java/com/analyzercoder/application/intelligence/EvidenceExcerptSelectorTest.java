package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceExcerptSelectorTest {
    @Test
    void findsMatchBeyondBudgetInsideASingleLongLine() {
        String content = "background ".repeat(500) + "refund policy" + " after ".repeat(500);
        var excerpt = EvidenceExcerptSelector.select(content, List.of("refund policy"), 400);
        assertTrue(excerpt.text().contains("refund policy"));
        assertTrue(content.contains(excerpt.text()));
        assertTrue(excerpt.text().length() <= 400);
    }

    @Test
    void selectsMatchingLateParagraphWithExactSourceLines() {
        String content =
                "Background without relevant terms.\n\n".repeat(100)
                        + "## Billing\n\nRefund policy: allow refund within seven days.\n\n"
                        + "Other information.\n\n".repeat(30);
        var excerpt = EvidenceExcerptSelector.select(content, List.of("refund", "policy"), 400);
        assertTrue(excerpt.text().contains("Refund policy"));
        assertTrue(content.contains(excerpt.text()));
        assertTrue(excerpt.text().length() <= 400);
        assertTrue(excerpt.startLine() > 150);
        assertEquals(
                excerpt.text(),
                content.substring(
                        content.indexOf(excerpt.text()),
                        content.indexOf(excerpt.text()) + excerpt.text().length()));
    }

    @Test
    void preservesCompleteMermaidAndCodeFencesWithinBudget() {
        String diagram = "```mermaid\ngraph TD\n\nStart --> Refund\n```\n\n";
        String content = "Unrelated introduction.\n\n".repeat(50) + diagram;
        var excerpt = EvidenceExcerptSelector.select(content, List.of("refund"), 200);
        assertTrue(excerpt.text().contains(diagram));
    }

    @Test
    void locatesLineInsideOversizedFunctionAndHandlesNoMatch() {
        String content =
                "void process() {\n" + "  prepare();\n".repeat(500) + "  refundPayment();\n}\n";
        var excerpt = EvidenceExcerptSelector.select(content, List.of("refundpayment"), 400);
        assertTrue(excerpt.text().contains("refundPayment"));
        assertTrue(excerpt.text().length() <= 400);
        assertEquals("small", EvidenceExcerptSelector.select("small", List.of(), 20).text());
        assertTrue(EvidenceExcerptSelector.select(content, List.of(), 100).text().length() <= 100);
    }
}
