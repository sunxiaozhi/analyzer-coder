package com.analyzercoder.application.pullrequest;

import com.analyzercoder.application.review.KnowledgeMatch;
import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import java.util.List;
import org.springframework.stereotype.Component;

/** 只渲染已保存审查中的确定性结论和明确未知项；模型文字不作为合并门禁。 */
@Component
public class PullRequestReviewCommentRenderer {
    private static final int MAX_ITEMS = 20;

    public String render(String marker, PullRequestProvider.PullRequestSnapshot source, TaskReviewResult review) {
        StringBuilder markdown = new StringBuilder(marker).append('\n');
        markdown.append("## 代码知识平台 · 提示性审查\n\n");
        markdown.append("> 这是一份基于当前已发布代码快照与正式工程知识的提示，不是合并门禁，也不会提交失败状态。\n\n");
        markdown.append("- 提供方：").append(source.provider()).append(" · ").append(escape(source.externalId())).append('\n');
        markdown.append("- 版本：`").append(shortCommit(source.baseSha())).append("` → `").append(shortCommit(source.headSha())).append("`\n");
        markdown.append("- 审查记录：`").append(review.reviewId()).append("`\n");
        if (review.status() == TaskReviewResult.Status.FAILED) {
            markdown.append("\n### 审查未完成\n\n- `")
                    .append(escape(review.error() == null ? "TASK_REVIEW_FAILED" : review.error().code()))
                    .append("` ")
                    .append(escape(review.error() == null ? "无法生成确定性结论" : review.error().message()))
                    .append("\n");
            return markdown.toString();
        }
        knowledge(markdown, "适用知识", review.applicableKnowledge());
        findings(markdown, "必须测试（尚未报告执行结果）", review.requiredTests());
        findings(markdown, "必要审批", review.requiredApprovals());
        knowledge(markdown, "可能失效的知识", review.staleKnowledge());
        findings(markdown, "未知项", review.unknowns());
        if (review.change() != null && review.change().partial()) {
            markdown.append("\n### 数据限制\n\n");
            review.change().limitations().stream()
                    .limit(MAX_ITEMS)
                    .forEach(
                            limitation ->
                                    markdown.append("- `")
                                            .append(escape(limitation.code()))
                                            .append("` ")
                                            .append(escape(limitation.detail()))
                                            .append('\n'));
        }
        markdown.append("\n---\n重新运行同一 PR/MR 审查会更新本评论，不会重复刷屏。\n");
        return markdown.toString();
    }

    private static void knowledge(
            StringBuilder markdown, String title, List<KnowledgeMatch> items) {
        markdown.append("\n### ").append(title).append(" (").append(items.size()).append(")\n\n");
        if (items.isEmpty()) {
            markdown.append("- 无\n");
            return;
        }
        items.stream()
                .limit(MAX_ITEMS)
                .forEach(
                        item ->
                                markdown.append("- **")
                                        .append(escape(item.title()))
                                        .append("** · `")
                                        .append(item.enforcement())
                                        .append("` · ")
                                        .append(item.reasons().size())
                                        .append(" 条代码证据\n"));
        omitted(markdown, items.size());
    }

    private static void findings(
            StringBuilder markdown, String title, List<TaskReviewFinding> items) {
        markdown.append("\n### ").append(title).append(" (").append(items.size()).append(")\n\n");
        if (items.isEmpty()) {
            markdown.append("- 无\n");
            return;
        }
        items.stream()
                .limit(MAX_ITEMS)
                .forEach(
                        item -> {
                            markdown.append("- **").append(escape(item.title())).append("** · `")
                                    .append(item.status()).append('`');
                            if (item.unknownReason() != null) {
                                markdown.append(" · ").append(escape(item.unknownReason().detail()));
                            } else {
                                markdown.append(" · ").append(item.evidence().size()).append(" 条证据");
                            }
                            markdown.append('\n');
                        });
        omitted(markdown, items.size());
    }

    private static void omitted(StringBuilder markdown, int size) {
        if (size > MAX_ITEMS) {
            markdown.append("- 另有 ").append(size - MAX_ITEMS).append(" 项，请在平台查看完整记录\n");
        }
    }

    private static String shortCommit(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(12, value.length()));
    }

    private static String escape(String value) {
        if (value == null) {
            return "未知";
        }
        return value.replace("\r", " ")
                .replace("\n", " ")
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
