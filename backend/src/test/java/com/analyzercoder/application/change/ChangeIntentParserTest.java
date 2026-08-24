package com.analyzercoder.application.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.llm.LlmSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeIntentParserTest {

    @Test
    void parsesAndBoundsStructuredModelOutput() {
        LlmSettingsService llm = mock(LlmSettingsService.class);
        UUID modelId = UUID.randomUUID();
        String answer =
                """
                ```json
                {
                  "changeType": "FEATURE",
                  "goal": "登录连续失败五次后锁定账号",
                  "domains": ["认证"],
                  "entities": ["登录", "失败次数"],
                  "candidateSymbols": ["AuthService"],
                  "constraints": ["不能影响单点登录"],
                  "expectedImpacts": ["认证流程", "配置", "测试"],
                  "unknowns": ["锁定时长未明确"],
                  "searchQueries": ["AuthService login failure", "登录 失败次数 锁定"]
                }
                ```
                """;
        when(llm.generate(eq(modelId), startsWith("你是代码变更意图解析器")))
                .thenReturn(Optional.of(new LlmSettingsService.GenerationResult(answer, "demo/model")));

        ChangeIntentParser.IntentInterpretation result =
                new ChangeIntentParser(llm, new ObjectMapper())
                        .parse("限制登录失败次数", modelId);

        assertThat(result.parserMode()).isEqualTo("MODEL");
        assertThat(result.provider()).isEqualTo("demo/model");
        assertThat(result.changeType()).isEqualTo("FEATURE");
        assertThat(result.candidateSymbols()).containsExactly("AuthService");
        assertThat(result.searchQueries())
                .containsExactly("AuthService login failure", "登录 失败次数 锁定");
    }

    @Test
    void fallsBackWhenModelOutputIsNotValidJson() {
        LlmSettingsService llm = mock(LlmSettingsService.class);
        UUID modelId = UUID.randomUUID();
        when(llm.generate(eq(modelId), startsWith("你是代码变更意图解析器")))
                .thenReturn(
                        Optional.of(
                                new LlmSettingsService.GenerationResult(
                                        "我认为应该修改 AuthService", "demo/model")));

        ChangeIntentParser.IntentInterpretation result =
                new ChangeIntentParser(llm, new ObjectMapper())
                        .parse("修复 AuthService 登录异常", modelId);

        assertThat(result.parserMode()).isEqualTo("RULES");
        assertThat(result.fallbackReason()).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(result.changeType()).isEqualTo("BUGFIX");
        assertThat(result.candidateSymbols()).contains("AuthService");
    }

    @Test
    void usesRulesExplicitlyWhenNoModelWasSelected() {
        LlmSettingsService llm = mock(LlmSettingsService.class);

        ChangeIntentParser.IntentInterpretation result =
                new ChangeIntentParser(llm, new ObjectMapper())
                        .parse("增加订单导出配置开关", null);

        assertThat(result.parserMode()).isEqualTo("RULES");
        assertThat(result.fallbackReason()).isEqualTo("MODEL_NOT_SELECTED");
        assertThat(result.changeType()).isEqualTo("CONFIG");
        assertThat(result.expectedImpacts()).contains("配置项与默认值");
    }
}
