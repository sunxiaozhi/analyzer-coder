package com.analyzercoder.application.intelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 融合向量、关键词与代码图谱得分，对候选证据去重并生成稳定排序。 */
@Component
public class RetrievalRanker {
    private static final double RRF_K = 60.0;
    private static final double MIN_LEXICAL_SCORE = 0.16;
    private static final double MIN_SEMANTIC_SCORE = 0.34;

    public List<RankedCandidate> fuse(List<ChannelResult> channels, int limit) {
        Map<String, MutableCandidate> merged = new LinkedHashMap<>();
        for (ChannelResult channel : channels) {
            int rank = 1;
            for (Candidate candidate : channel.candidates()) {
                MutableCandidate item =
                        merged.computeIfAbsent(
                                candidate.key(), ignored -> new MutableCandidate(candidate));
                item.channels.add(channel.channel());
                item.lexicalScore = Math.max(item.lexicalScore, candidate.lexicalScore());
                item.semanticScore = Math.max(item.semanticScore, candidate.semanticScore());
                item.rrfScore += channel.weight() / (RRF_K + rank++);
            }
        }

        return merged.values().stream()
                .filter(
                        item ->
                                item.lexicalScore >= MIN_LEXICAL_SCORE
                                        || item.semanticScore >= MIN_SEMANTIC_SCORE)
                .map(MutableCandidate::ranked)
                .sorted(
                        Comparator.comparingDouble(RankedCandidate::score)
                                .reversed()
                                .thenComparing(RankedCandidate::key))
                .limit(Math.max(1, limit))
                .toList();
    }

    public record Candidate(
            String key,
            String sourceType,
            Map<String, Object> row,
            double lexicalScore,
            double semanticScore) {
        public Candidate {
            row = Map.copyOf(row);
        }
    }

    public record ChannelResult(String channel, double weight, List<Candidate> candidates) {
        public ChannelResult {
            candidates = List.copyOf(candidates);
        }
    }

    public record RankedCandidate(
            String key,
            String sourceType,
            Map<String, Object> row,
            double score,
            double lexicalScore,
            double semanticScore,
            List<String> channels) {}

    private static final class MutableCandidate {
        private final Candidate candidate;
        private final Set<String> channels = new LinkedHashSet<>();
        private double lexicalScore;
        private double semanticScore;
        private double rrfScore;

        private MutableCandidate(Candidate candidate) {
            this.candidate = candidate;
            this.lexicalScore = candidate.lexicalScore();
            this.semanticScore = candidate.semanticScore();
        }

        private RankedCandidate ranked() {
            double confidence = Math.max(lexicalScore, semanticScore);
            double normalizedRrf = Math.min(1.0, rrfScore * RRF_K);
            double score = confidence * 0.72 + normalizedRrf * 0.28;
            return new RankedCandidate(
                    candidate.key(),
                    candidate.sourceType(),
                    candidate.row(),
                    score,
                    lexicalScore,
                    semanticScore,
                    new ArrayList<>(channels));
        }
    }
}
