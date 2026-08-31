package com.analyzercoder.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EngineeringKnowledgePolicyTest {
    private final EngineeringKnowledgePolicy policy = new EngineeringKnowledgePolicy();

    @Test
    void defaultsKeepLegacyCardsAsNonEnforcingReferenceKnowledge() {
        EngineeringKnowledgePolicy.ValidatedKnowledge result =
                policy.validate(null, null, null, null, null, null);

        assertThat(result.kind()).isEqualTo(KnowledgeKind.REFERENCE);
        assertThat(result.severity()).isEqualTo(KnowledgeSeverity.INFO);
        assertThat(result.enforcement()).isEqualTo(KnowledgeEnforcement.REFERENCE);
        assertThat(result.scope().isEmpty()).isTrue();
        assertThat(result.obligations().isEmpty()).isTrue();
    }

    @Test
    void normalizesRepositoryRelativeScopeWithoutLosingMeaning() {
        UUID repositoryId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        EngineeringKnowledgePolicy.ValidatedKnowledge result =
                policy.validate(
                        "business_rule",
                        "warning",
                        "advisory",
                        null,
                        new KnowledgeScope(
                                List.of(
                                        " backend\\src\\**\\refund\\** ",
                                        "backend/src/**/refund/**"),
                                List.of(" RefundService "),
                                List.of(" backend "),
                                List.of(repositoryId),
                                List.of(" ", "Order-Service"),
                                List.of(contractId)),
                        new KnowledgeObligations(
                                List.of(" ./mvnw test "), List.of(), List.of("检查退款边界")));

        assertThat(result.kind()).isEqualTo(KnowledgeKind.BUSINESS_RULE);
        assertThat(result.scope().pathPatterns()).containsExactly("backend/src/**/refund/**");
        assertThat(result.scope().symbols()).containsExactly("RefundService");
        assertThat(result.scope().repositoryIds()).containsExactly(repositoryId);
        assertThat(result.scope().serviceNames()).containsExactly("order-service");
        assertThat(result.scope().contractIds()).containsExactly(contractId);
        assertThat(result.obligations().requiredTests()).containsExactly("./mvnw test");
    }

    @Test
    void rejectsUnsafePathsAndObligationsOnReferenceKnowledge() {
        assertThatThrownBy(
                        () ->
                                policy.validate(
                                        "BUSINESS_RULE",
                                        "INFO",
                                        "ADVISORY",
                                        null,
                                        new KnowledgeScope(
                                                List.of("../secrets.txt"), List.of(), List.of()),
                                        KnowledgeObligations.empty()))
                .hasMessageContaining("仓库相对路径");

        assertThatThrownBy(
                        () ->
                                policy.validate(
                                        "REFERENCE",
                                        "INFO",
                                        "REFERENCE",
                                        null,
                                        KnowledgeScope.empty(),
                                        new KnowledgeObligations(
                                                List.of("npm test"), List.of(), List.of())))
                .hasMessageContaining("参考知识不能设置");

        assertThatThrownBy(
                        () ->
                                policy.validate(
                                        "BUSINESS_RULE",
                                        "WARNING",
                                        "REQUIRED",
                                        UUID.randomUUID(),
                                        new KnowledgeScope(List.of("src/**"), List.of(), List.of()),
                                        new KnowledgeObligations(
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of("../production.env"),
                                                false)))
                .hasMessageContaining("仓库相对路径");
    }

    @Test
    void rejectsUnknownEnumsAndOversizedJsonCollections() {
        assertThatThrownBy(
                        () ->
                                policy.validate(
                                        "NOT_A_KIND",
                                        "INFO",
                                        "REFERENCE",
                                        null,
                                        KnowledgeScope.empty(),
                                        KnowledgeObligations.empty()))
                .hasMessageContaining("类型无效");

        List<String> tooManyPaths =
                IntStream.rangeClosed(1, 51).mapToObj(index -> "src/path-" + index).toList();
        assertThatThrownBy(
                        () ->
                                policy.validate(
                                        "BUSINESS_RULE",
                                        "WARNING",
                                        "ADVISORY",
                                        null,
                                        new KnowledgeScope(tooManyPaths, List.of(), List.of()),
                                        KnowledgeObligations.empty()))
                .hasMessageContaining("适用路径最多允许 50 项");
    }

    @Test
    void requiredKnowledgeNeedsOwnerScopeApprovalAndCurrentEvidenceToPublish() {
        UUID owner = UUID.randomUUID();
        KnowledgeScope scope = new KnowledgeScope(List.of("backend/src/**"), List.of(), List.of());

        assertThatThrownBy(
                        () ->
                                policy.validateForPublication(
                                        KnowledgeEnforcement.REQUIRED,
                                        null,
                                        scope,
                                        "APPROVED",
                                        "CURRENT"))
                .hasMessageContaining("负责人");
        assertThatThrownBy(
                        () ->
                                policy.validateForPublication(
                                        KnowledgeEnforcement.REQUIRED,
                                        owner,
                                        KnowledgeScope.empty(),
                                        "APPROVED",
                                        "CURRENT"))
                .hasMessageContaining("适用范围");
        assertThatThrownBy(
                        () ->
                                policy.validateForPublication(
                                        KnowledgeEnforcement.REQUIRED,
                                        owner,
                                        scope,
                                        "APPROVED",
                                        "UNVERIFIED"))
                .hasMessageContaining("当前代码快照");

        policy.validateForPublication(
                KnowledgeEnforcement.REQUIRED, owner, scope, "APPROVED", "CURRENT");
    }
}
