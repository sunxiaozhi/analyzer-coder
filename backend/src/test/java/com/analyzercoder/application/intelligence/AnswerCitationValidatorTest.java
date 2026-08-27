package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnswerCitationValidatorTest {
    private final AnswerCitationValidator validator = new AnswerCitationValidator();

    @Test
    void acceptsOnlyExistingEvidenceReferences() {
        var validation = validator.validate("事实一 [S1]，事实二 [S2]。", 2);

        assertTrue(validation.valid());
        assertTrue(validation.complete());
        assertEquals(2, validation.citedEvidence().size());
        assertEquals(1.0d, validation.assessment().coverageRate());
    }

    @Test
    void rejectsAnswersWithoutCitations() {
        assertFalse(validator.validate("没有引用的结论", 2).valid());
    }

    @Test
    void rejectsOutOfRangeCitations() {
        var validation = validator.validate("不存在的证据 [S3]", 2);

        assertFalse(validation.valid());
        assertEquals(java.util.List.of("S3"), validation.assessment().invalidReferences());
    }

    @Test
    void reportsIncompleteCoverageWhenOnlyLastParagraphHasCitation() {
        var validation = validator.validate("第一段是仓库事实。\n\n第二段也是仓库事实 [S1]。", 2);

        assertTrue(validation.valid());
        assertFalse(validation.complete());
        assertEquals(2, validation.assessment().factualBlockCount());
        assertEquals(1, validation.assessment().uncitedBlockCount());
        assertEquals(0.5d, validation.assessment().coverageRate());
        assertFalse(validation.assessment().entailmentVerified());
    }

    @Test
    void evaluatesMarkdownListItemsSeparately() {
        var validation = validator.validate("- 已引用的事实 [S1]\n- 未引用的事实", 1);

        assertTrue(validation.valid());
        assertFalse(validation.complete());
        assertEquals(2, validation.assessment().factualBlockCount());
        assertEquals(1, validation.assessment().uncitedBlockCount());
    }

    @Test
    void reportsMalformedAndOversizedReferencesWithoutThrowing() {
        var validation = validator.validate("错误引用 [Sabc] 和 [S999999999999999999999]", 1);

        assertFalse(validation.valid());
        assertEquals(
                java.util.List.of("Sabc", "S999999999999999999999"),
                validation.assessment().invalidReferences());
    }
}
