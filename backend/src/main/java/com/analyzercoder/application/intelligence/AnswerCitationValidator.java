package com.analyzercoder.application.intelligence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 机械校验回答的引用编号和段落覆盖；不判断证据是否在语义上支持回答。 */
@Component
public class AnswerCitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\[S([^]\\r\\n]+)]");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+");
    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s+.+$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*(?:[-*_]\\s*){3,}$");
    private static final Pattern SUBSTANTIVE_TEXT = Pattern.compile(".*[\\p{L}\\p{N}].*", Pattern.DOTALL);

    public Validation validate(String answer, int evidenceCount) {
        if (answer == null || answer.isBlank()) {
            return Validation.invalid("模型没有返回回答", CitationAssessment.empty());
        }
        Matcher matcher = CITATION.matcher(answer);
        Set<Integer> cited = new LinkedHashSet<>();
        Set<String> invalidReferences = new LinkedHashSet<>();
        while (matcher.find()) {
            String reference = matcher.group(1).strip();
            Integer index = parseReference(reference);
            if (index == null || index < 1 || index > evidenceCount) {
                invalidReferences.add("S" + reference);
            } else {
                cited.add(index);
            }
        }

        List<String> factualBlocks = factualBlocks(answer);
        int citedBlockCount = 0;
        for (String block : factualBlocks) {
            Matcher blockMatcher = CITATION.matcher(block);
            boolean hasValidReference = false;
            while (blockMatcher.find()) {
                Integer index = parseReference(blockMatcher.group(1).strip());
                if (index != null && index >= 1 && index <= evidenceCount) {
                    hasValidReference = true;
                    break;
                }
            }
            if (hasValidReference) citedBlockCount++;
        }
        int factualBlockCount = factualBlocks.size();
        int uncitedBlockCount = factualBlockCount - citedBlockCount;
        double coverageRate = factualBlockCount == 0 ? 0.0d : (double) citedBlockCount / factualBlockCount;
        CitationAssessment assessment = new CitationAssessment(
                factualBlockCount,
                citedBlockCount,
                uncitedBlockCount,
                coverageRate,
                List.copyOf(invalidReferences),
                false);

        if (!invalidReferences.isEmpty()) {
            return Validation.invalid("模型引用了不存在的证据", assessment);
        }
        if (cited.isEmpty()) {
            return Validation.invalid("模型回答缺少证据引用", assessment);
        }
        return new Validation(true, uncitedBlockCount == 0, List.copyOf(cited), assessment, null);
    }

    private static List<String> factualBlocks(String answer) {
        return Pattern.compile("(?:\\R\\s*){2,}")
                .splitAsStream(answer.strip())
                .flatMap(block -> {
                    List<String> lines = block.lines().filter(line -> !line.isBlank()).toList();
                    if (!lines.isEmpty() && lines.stream().allMatch(line -> LIST_ITEM.matcher(line).matches())) {
                        return lines.stream();
                    }
                    return java.util.stream.Stream.of(block);
                })
                .map(String::strip)
                .filter(AnswerCitationValidator::isFactualBlock)
                .toList();
    }

    private static boolean isFactualBlock(String block) {
        if (HEADING.matcher(block).matches() || HORIZONTAL_RULE.matcher(block).matches()) return false;
        String withoutCitations = CITATION.matcher(block).replaceAll("");
        return SUBSTANTIVE_TEXT.matcher(withoutCitations).matches();
    }

    private static Integer parseReference(String reference) {
        if (!reference.matches("\\d+")) return null;
        try {
            return Integer.valueOf(reference);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Validation(
            boolean valid,
            boolean complete,
            List<Integer> citedEvidence,
            CitationAssessment assessment,
            String reason) {
        private static Validation invalid(String reason, CitationAssessment assessment) {
            return new Validation(false, false, List.of(), assessment, reason);
        }
    }
}
