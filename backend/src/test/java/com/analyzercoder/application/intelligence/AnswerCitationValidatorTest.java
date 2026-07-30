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
        assertEquals(2, validation.citedEvidence().size());
    }

    @Test
    void rejectsAnswersWithoutCitations() {
        assertFalse(validator.validate("没有引用的结论", 2).valid());
    }

    @Test
    void rejectsOutOfRangeCitations() {
        assertFalse(validator.validate("不存在的证据 [S3]", 2).valid());
    }
}
