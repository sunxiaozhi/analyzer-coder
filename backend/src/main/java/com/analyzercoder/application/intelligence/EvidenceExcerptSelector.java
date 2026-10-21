package com.analyzercoder.application.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Selects an exact, contiguous source passage; never asks a model to rewrite evidence. */
final class EvidenceExcerptSelector {
    private EvidenceExcerptSelector() {}

    static Excerpt select(String content, List<String> terms, int limit) {
        if (content.length() <= limit) return excerpt(content, 0, content.length());
        List<Integer> boundaries = new ArrayList<>(List.of(0));
        boolean fenced = false;
        String fence = "";
        int offset = 0;
        for (String line : content.split("(?<=\n)", -1)) {
            String trimmed = line.stripLeading();
            if (!fenced && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                fenced = true;
                fence = trimmed.substring(0, 3);
            } else if (fenced && trimmed.startsWith(fence)) {
                fenced = false;
            }
            offset += line.length();
            if (!fenced && line.isBlank() && offset > boundaries.get(boundaries.size() - 1)) {
                boundaries.add(offset);
            }
        }
        if (boundaries.get(boundaries.size() - 1) != content.length())
            boundaries.add(content.length());
        int best = 0;
        double bestScore = -1;
        for (int i = 0; i + 1 < boundaries.size(); i++) {
            String block = content.substring(boundaries.get(i), boundaries.get(i + 1));
            double score = score(block, terms);
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        int start = boundaries.get(best), end = boundaries.get(best + 1);
        if (end - start > limit) {
            // Oversized paragraphs/functions: choose a line window around the strongest match.
            int anchor = start;
            bestScore = -1;
            for (int pos = start; pos < end; ) {
                int newline = content.indexOf('\n', pos);
                int lineEnd = newline < 0 ? end : Math.min(end, newline + 1);
                double score = score(content.substring(pos, lineEnd), terms);
                if (score > bestScore) {
                    String line = content.substring(pos, lineEnd).toLowerCase(Locale.ROOT);
                    String strongest =
                            terms.stream()
                                    .filter(line::contains)
                                    .max(java.util.Comparator.comparingInt(String::length))
                                    .orElse("");
                    anchor = pos + (strongest.isEmpty() ? 0 : line.indexOf(strongest));
                    bestScore = score;
                }
                pos = lineEnd;
            }
            int blockStart = start, blockEnd = end;
            start = Math.max(blockStart, anchor - limit / 3);
            int newline = content.indexOf('\n', start);
            if (start > blockStart && newline >= start && newline < anchor) start = newline + 1;
            end = Math.min(blockEnd, start + limit);
            int lastNewline = content.lastIndexOf('\n', end - 1);
            if (end < blockEnd && lastNewline > anchor) end = lastNewline + 1;
        } else {
            int left = best, right = best + 1;
            while (left > 0 || right < boundaries.size() - 1) {
                boolean grew = false;
                if (left > 0 && end - boundaries.get(left - 1) <= limit) {
                    start = boundaries.get(--left);
                    grew = true;
                }
                if (right < boundaries.size() - 1 && boundaries.get(right + 1) - start <= limit) {
                    end = boundaries.get(++right);
                    grew = true;
                }
                if (!grew) break;
            }
        }
        if (start > 0 && Character.isLowSurrogate(content.charAt(start))) start++;
        if (end < content.length() && Character.isLowSurrogate(content.charAt(end))) end--;
        return excerpt(content, start, end);
    }

    private static double score(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        double matches =
                terms.stream()
                        .filter(term -> lower.contains(term.toLowerCase(Locale.ROOT)))
                        .mapToDouble(term -> Math.min(12, term.length()))
                        .sum();
        return matches / Math.sqrt(Math.max(80, text.length()));
    }

    private static Excerpt excerpt(String content, int start, int end) {
        int startLine =
                1 + (int) content.substring(0, start).chars().filter(c -> c == '\n').count();
        int endLine =
                startLine
                        + (int)
                                content.substring(start, end)
                                        .stripTrailing()
                                        .chars()
                                        .filter(c -> c == '\n')
                                        .count();
        return new Excerpt(content.substring(start, end), startLine, endLine);
    }

    record Excerpt(String text, int startLine, int endLine) {}
}
