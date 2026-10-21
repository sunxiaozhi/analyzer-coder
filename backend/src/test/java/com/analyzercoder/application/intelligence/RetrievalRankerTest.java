package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalRankerTest {
    private final RetrievalRanker ranker = new RetrievalRanker();

    @Test
    void filtersUnrelatedSemanticCandidates() {
        var weak = candidate("CODE:weak", 0, 0.12);

        assertTrue(
                ranker.fuse(
                                List.of(
                                        new RetrievalRanker.ChannelResult(
                                                "CODE_SEMANTIC", 1, List.of(weak))),
                                10)
                        .isEmpty());
    }

    @Test
    void rewardsCandidatesConfirmedByMultipleChannels() {
        var confirmedKeyword = candidate("CODE:confirmed", 0.6, 0);
        var confirmedSemantic = candidate("CODE:confirmed", 0, 0.72);
        var keywordOnly = candidate("CODE:keyword", 0.6, 0);

        var ranked =
                ranker.fuse(
                        List.of(
                                new RetrievalRanker.ChannelResult(
                                        "CODE_KEYWORD", 1, List.of(confirmedKeyword, keywordOnly)),
                                new RetrievalRanker.ChannelResult(
                                        "CODE_SEMANTIC", 1, List.of(confirmedSemantic))),
                        10);

        assertEquals("CODE:confirmed", ranked.get(0).key());
        assertEquals(List.of("CODE_KEYWORD", "CODE_SEMANTIC"), ranked.get(0).channels());
    }

    private static RetrievalRanker.Candidate candidate(
            String key, double lexical, double semantic) {
        return new RetrievalRanker.Candidate(key, "CODE", Map.of("id", key), lexical, semantic);
    }

    @Test
    void replacesSameOriginMarkdownAndBackfillsWithoutAddingDuplicateScores() {
        var code = source("raw", "CODE", 0.9, Map.of());
        var knowledge = source("card", "KNOWLEDGE", 0.7, Map.of());
        var extra = source("other", "CODE", 0.6, Map.of("file_path", "other.md"));
        var ranked =
                ranker.fuse(
                        List.of(
                                new RetrievalRanker.ChannelResult(
                                        "keyword", 1, List.of(code, knowledge, extra))),
                        2);
        assertEquals(
                List.of("card", "other"),
                ranked.stream().map(RetrievalRanker.RankedCandidate::key).toList());
        assertEquals(1, RetrievalRanker.mergedSources(ranked.get(0).row()).size());
        var original =
                ranker.fuse(
                        List.of(new RetrievalRanker.ChannelResult("keyword", 1, List.of(code))), 1);
        assertEquals(original.get(0).score(), ranked.get(0).score());
    }

    @Test
    void preservesOtherBranchesVersionsPathsAndAdditionalOrConflictingText() {
        var card = source("card", "KNOWLEDGE", 0.8, Map.of());
        for (Map<String, Object> changes :
                List.<Map<String, Object>>of(
                        Map.of("branch_id", "other"),
                        Map.of("content_version", "other"),
                        Map.of("repo_id", "other"),
                        Map.of("file_path", "other.md"),
                        Map.of("content", "Different rule. ".repeat(10)),
                        Map.of("content", "Original rule. ".repeat(10) + "Additional detail."))) {
            var code = source("code", "CODE", 0.9, changes);
            assertEquals(
                    2,
                    ranker.fuse(
                                    List.of(
                                            new RetrievalRanker.ChannelResult(
                                                    "keyword", 1, List.of(code, card))),
                                    10)
                            .size());
        }
    }

    @Test
    void onlyBoostsCuratedRelevantKnowledgeAndKeepsExactCodeAhead() {
        var curated = source("curated", "KNOWLEDGE", .7, Map.of("curated_knowledge", true));
        var automatic = source("automatic", "KNOWLEDGE", .7, Map.of("source_path", "other.md"));
        var code = source("code", "CODE", .98, Map.of("file_path", "Example.java"));
        var weak = source("weak", "KNOWLEDGE", .1, Map.of("curated_knowledge", true));
        var ranked =
                ranker.fuse(
                        List.of(
                                new RetrievalRanker.ChannelResult(
                                        "keyword", 1, List.of(automatic, curated, code, weak))),
                        10);
        assertEquals(
                List.of("code", "curated", "automatic"),
                ranked.stream().map(RetrievalRanker.RankedCandidate::key).toList());
    }

    @Test
    void duplicateChannelRowsDoNotBoostRanking() {
        var code = candidate("code", .7, 0);
        var once =
                ranker.fuse(
                        List.of(new RetrievalRanker.ChannelResult("keyword", 1, List.of(code))),
                        10);
        var twice =
                ranker.fuse(
                        List.of(
                                new RetrievalRanker.ChannelResult(
                                        "keyword", 1, List.of(code, code))),
                        10);
        assertEquals(once, twice);
    }

    @Test
    void onlyCollapsesIdenticalKnowledgeWithSameProvenanceRevisionAndScope() {
        var first = source("first", "KNOWLEDGE", .8, Map.of());
        var same = source("same", "KNOWLEDGE", .7, Map.of());
        var revision = source("revision", "KNOWLEDGE", .6, Map.of("revision", 2));
        var scope = source("scope", "KNOWLEDGE", .6, Map.of("source_scope", "分支知识"));
        assertEquals(
                3,
                ranker.fuse(
                                List.of(
                                        new RetrievalRanker.ChannelResult(
                                                "keyword",
                                                1,
                                                List.of(first, same, revision, scope))),
                                10)
                        .size());
    }

    private static RetrievalRanker.Candidate source(
            String key, String type, double score, Map<String, Object> changes) {
        Map<String, Object> row =
                new HashMap<>(
                        Map.of(
                                "id",
                                key,
                                "repo_id",
                                "repo",
                                "content",
                                "Original rule. ".repeat(10),
                                "revision",
                                1));
        if (type.equals("CODE"))
            row.putAll(
                    Map.of(
                            "branch_id",
                            "main",
                            "content_version",
                            "v1",
                            "file_path",
                            "docs/rules.md"));
        else
            row.putAll(
                    Map.of(
                            "source_branch_id",
                            "main",
                            "source_content_version",
                            "v1",
                            "source_path",
                            "docs/rules.md"));
        row.putAll(changes);
        return new RetrievalRanker.Candidate(key, type, row, score, 0);
    }
}
