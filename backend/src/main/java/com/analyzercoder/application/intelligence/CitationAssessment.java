package com.analyzercoder.application.intelligence;

import java.util.List;

/** 引用覆盖的机械评估结果；不代表证据在语义上蕴含回答。 */
public record CitationAssessment(
        int factualBlockCount,
        int citedBlockCount,
        int uncitedBlockCount,
        double coverageRate,
        List<String> invalidReferences,
        boolean entailmentVerified) {

    public CitationAssessment {
        invalidReferences = invalidReferences == null ? List.of() : List.copyOf(invalidReferences);
    }

    public static CitationAssessment empty() {
        return new CitationAssessment(0, 0, 0, 0.0d, List.of(), false);
    }
}
