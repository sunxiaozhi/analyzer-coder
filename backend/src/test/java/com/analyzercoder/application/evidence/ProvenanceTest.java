package com.analyzercoder.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvenanceTest {
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();

    @Test
    void requiresVersionForCodeFacts() {
        assertThatThrownBy(
                        () ->
                                Provenance.codeFact(
                                        repositoryId,
                                        null,
                                        null,
                                        null,
                                        "src/Auth.java",
                                        "Auth",
                                        "CLASS",
                                        1,
                                        12,
                                        "a".repeat(64),
                                        "code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本信息");
    }

    @Test
    void requiresKnowledgeRevisionAndReviewStatus() {
        assertThatThrownBy(
                        () ->
                                Provenance.verifiedKnowledge(
                                        repositoryId, UUID.randomUUID(), 0, null, "knowledge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("修订号");
    }

    @Test
    void graphInferenceCarriesArtifactAndRelationPath() {
        Provenance provenance =
                Provenance.graphInference(
                        repositoryId,
                        snapshotId,
                        "a".repeat(40),
                        snapshotId + ":architecture-map",
                        List.of("src/Auth.java", "backend/application"),
                        "src/Auth.java",
                        "module mapping");

        assertThat(provenance.sourceType()).isEqualTo(TruthSource.GRAPH_INFERENCE);
        assertThat(provenance.graphArtifactId()).contains(snapshotId.toString());
        assertThat(provenance.relationPath())
                .containsExactly("src/Auth.java", "backend/application");
    }

    @Test
    void modelSuggestionMustReferenceFinding() {
        assertThatThrownBy(() -> Provenance.modelSuggestion(repositoryId, null, "summary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Finding");
    }
}
