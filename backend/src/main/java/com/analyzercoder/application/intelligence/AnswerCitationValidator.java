package com.analyzercoder.application.intelligence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 校验模型答案中的引用编号，仅保留本次检索证据集中真实存在的引用。 */
@Component
public class AnswerCitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");

    public Validation validate(String answer, int evidenceCount) {
        if (answer == null || answer.isBlank()) {
            return Validation.invalid("模型没有返回回答");
        }
        Matcher matcher = CITATION.matcher(answer);
        Set<Integer> cited = new LinkedHashSet<>();
        boolean invalidReference = false;
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 1 || index > evidenceCount) {
                invalidReference = true;
            } else {
                cited.add(index);
            }
        }
        if (invalidReference) {
            return Validation.invalid("模型引用了不存在的证据");
        }
        if (cited.isEmpty()) {
            return Validation.invalid("模型回答缺少证据引用");
        }
        return new Validation(true, List.copyOf(cited), null);
    }

    public record Validation(boolean valid, List<Integer> citedEvidence, String reason) {
        private static Validation invalid(String reason) {
            return new Validation(false, List.of(), reason);
        }
    }
}
