package com.analyzercoder.application.intelligence;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 从自然语言问题中提取检索意图、关键词和范围约束，为混合检索生成结构化条件。 */
@Component
public class RetrievalQueryAnalyzer {
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}_$.-]+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]{4,}");
    private static final Set<String> STOP_WORDS =
            Set.of(
                    "the", "a", "an", "is", "are", "of", "to", "in", "for", "and", "or", "什么", "如何",
                    "怎么", "是否", "一个", "这个", "那个", "请问", "哪些", "哪里");
    private static final int MAX_TERMS = 12;

    public Query analyze(String value) {
        String normalized = normalize(value);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(normalized);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            addTerm(terms, matcher.group());
        }

        Matcher cjk = CJK.matcher(normalized);
        while (cjk.find() && terms.size() < MAX_TERMS) {
            String sequence = cjk.group();
            for (int width : List.of(4, 3, 2)) {
                for (int start = 0;
                        start + width <= sequence.length() && terms.size() < MAX_TERMS;
                        start++) {
                    addTerm(terms, sequence.substring(start, start + width));
                }
            }
        }

        return new Query(normalized, new ArrayList<>(terms));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void addTerm(Set<String> terms, String raw) {
        String term = raw == null ? "" : raw.trim();
        if (term.length() < 2 || term.length() > 80 || STOP_WORDS.contains(term)) {
            return;
        }
        terms.add(term);
        for (String camelPart : term.split("(?<=[a-z0-9])(?=[A-Z])|[_.$/-]+")) {
            String part = camelPart.toLowerCase(Locale.ROOT);
            if (part.length() >= 2 && !STOP_WORDS.contains(part)) {
                terms.add(part);
            }
        }
    }

    public record Query(String normalized, List<String> terms) {
        public Query {
            terms = List.copyOf(terms);
        }
    }
}
