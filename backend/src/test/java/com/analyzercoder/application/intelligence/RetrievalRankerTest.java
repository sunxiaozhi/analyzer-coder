package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalRankerTest {
    private final RetrievalRanker ranker = new RetrievalRanker();

    @Test
    void filtersUnrelatedSemanticCandidates() {
        var weak = candidate("CODE:weak", 0, 0.12);

        assertTrue(ranker.fuse(
            List.of(new RetrievalRanker.ChannelResult("CODE_SEMANTIC", 1, List.of(weak))),
            10
        ).isEmpty());
    }

    @Test
    void rewardsCandidatesConfirmedByMultipleChannels() {
        var confirmedKeyword = candidate("CODE:confirmed", 0.6, 0);
        var confirmedSemantic = candidate("CODE:confirmed", 0, 0.72);
        var keywordOnly = candidate("CODE:keyword", 0.6, 0);

        var ranked = ranker.fuse(List.of(
            new RetrievalRanker.ChannelResult("CODE_KEYWORD", 1, List.of(confirmedKeyword, keywordOnly)),
            new RetrievalRanker.ChannelResult("CODE_SEMANTIC", 1, List.of(confirmedSemantic))
        ), 10);

        assertEquals("CODE:confirmed", ranked.get(0).key());
        assertEquals(List.of("CODE_KEYWORD", "CODE_SEMANTIC"), ranked.get(0).channels());
    }

    private static RetrievalRanker.Candidate candidate(
        String key,
        double lexical,
        double semantic
    ) {
        return new RetrievalRanker.Candidate(
            key, "CODE", Map.of("id", key), lexical, semantic
        );
    }
}
