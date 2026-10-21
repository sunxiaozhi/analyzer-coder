package com.analyzercoder.application.intelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 融合向量、关键词与代码图谱得分，对候选证据去重并生成稳定排序。 */
@Component
public class RetrievalRanker {
    private static final double RRF_K = 60.0;
    private static final double MIN_LEXICAL_SCORE = 0.16;
    private static final double MIN_SEMANTIC_SCORE = 0.34;

    @Value("${app.retrieval.curated-knowledge-boost:0.04}")
    private double curatedKnowledgeBoost = 0.04;

    public List<RankedCandidate> fuse(List<ChannelResult> channels, int limit) {
        Map<String, MutableCandidate> merged = new LinkedHashMap<>();
        for (ChannelResult channel : channels) {
            int rank = 1;
            for (Candidate candidate : channel.candidates()) {
                MutableCandidate item =
                        merged.computeIfAbsent(
                                candidate.key(), ignored -> new MutableCandidate(candidate));
                if (!item.channels.add(channel.channel())) continue;
                item.lexicalScore = Math.max(item.lexicalScore, candidate.lexicalScore());
                item.semanticScore = Math.max(item.semanticScore, candidate.semanticScore());
                item.rrfScore += channel.weight() / (RRF_K + rank++);
            }
        }

        List<RankedCandidate> ranked =
                merged.values().stream()
                        .filter(
                                item ->
                                        item.lexicalScore >= MIN_LEXICAL_SCORE
                                                || item.semanticScore >= MIN_SEMANTIC_SCORE)
                        .map(item -> item.ranked(curatedKnowledgeBoost))
                        .sorted(
                                Comparator.comparingDouble(RankedCandidate::score)
                                        .reversed()
                                        .thenComparing(RankedCandidate::key))
                        .toList();
        return deduplicate(ranked).stream().limit(Math.max(1, limit)).toList();
    }

    private static List<RankedCandidate> deduplicate(List<RankedCandidate> ranked) {
        List<RankedCandidate> unique = new ArrayList<>();
        // Knowledge can replace a duplicate Markdown chunk even when it ranked below it.
        List<RankedCandidate> ordered = new ArrayList<>(ranked);
        ordered.sort(Comparator.comparing(item -> !"KNOWLEDGE".equals(item.sourceType())));
        for (RankedCandidate candidate : ordered) {
            boolean duplicate = false;
            for (int i = 0; i < unique.size(); i++) {
                RankedCandidate kept = unique.get(i);
                if (!coveredBy(candidate, kept)) continue;
                Map<String, Object> row = new LinkedHashMap<>(kept.row());
                List<Map<String, Object>> sources = new ArrayList<>(mergedSources(kept.row()));
                Map<String, Object> source = new LinkedHashMap<>(candidate.row());
                source.put("source_type", candidate.sourceType());
                sources.add(source);
                row.put("merged_sources", List.copyOf(sources));
                unique.set(
                        i,
                        new RankedCandidate(
                                kept.key(),
                                kept.sourceType(),
                                row,
                                Math.max(kept.score(), candidate.score()),
                                kept.lexicalScore(),
                                kept.semanticScore(),
                                kept.channels()));
                duplicate = true;
                break;
            }
            if (!duplicate) unique.add(candidate);
        }
        unique.sort(
                Comparator.comparingDouble(RankedCandidate::score)
                        .reversed()
                        .thenComparing(RankedCandidate::key));
        return unique;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mergedSources(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.getOrDefault("merged_sources", List.of());
    }

    private static boolean coveredBy(RankedCandidate item, RankedCandidate kept) {
        Map<String, Object> code = item.row(), target = kept.row();
        boolean knowledge = "KNOWLEDGE".equals(kept.sourceType());
        if ("KNOWLEDGE".equals(item.sourceType())) {
            return knowledge
                    && same(code, "repo_id", target, "repo_id")
                    && same(code, "source_branch_id", target, "source_branch_id")
                    && same(code, "source_content_version", target, "source_content_version")
                    && same(code, "source_path", target, "source_path")
                    && Objects.equals(code.get("source_scope"), target.get("source_scope"))
                    && same(code, "revision", target, "revision")
                    && same(code, "content", target, "content");
        }
        if (!same(code, "repo_id", target, "repo_id")
                || !same(code, "branch_id", target, knowledge ? "source_branch_id" : "branch_id")
                || !same(
                        code,
                        "content_version",
                        target,
                        knowledge ? "source_content_version" : "content_version")
                || !same(code, "file_path", target, knowledge ? "source_path" : "file_path"))
            return false;
        if (!knowledge
                && (!(code.get("start_line") instanceof Number start)
                        || !(code.get("end_line") instanceof Number end)
                        || !(target.get("start_line") instanceof Number keptStart)
                        || !(target.get("end_line") instanceof Number keptEnd)
                        || start.intValue() < keptStart.intValue()
                        || end.intValue() > keptEnd.intValue())) return false;
        String body = Objects.toString(code.get("content"), "").replaceAll("\\s+", " ").trim();
        String other = Objects.toString(target.get("content"), "").replaceAll("\\s+", " ").trim();
        // Do not conflate merely similar statements (especially negations or changed values).
        return body.length() >= 80 && other.contains(body);
    }

    private static boolean same(
            Map<String, Object> a, String ak, Map<String, Object> b, String bk) {
        return a.get(ak) != null && Objects.equals(a.get(ak), b.get(bk));
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

        private RankedCandidate ranked(double knowledgeBoost) {
            double confidence = Math.max(lexicalScore, semanticScore);
            double normalizedRrf = Math.min(1.0, rrfScore * RRF_K);
            double score = confidence * 0.72 + normalizedRrf * 0.28;
            if ("KNOWLEDGE".equals(candidate.sourceType())
                    && Boolean.TRUE.equals(candidate.row().get("curated_knowledge"))) {
                score =
                        Math.min(
                                1.0,
                                score + Math.max(0, Math.min(0.08, knowledgeBoost)) * confidence);
            }
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
