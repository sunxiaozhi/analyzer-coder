package com.analyzercoder.domain.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepositoryAssetClassifierTest {

    @Test
    void classifiesProjectAssetsByStablePathRules() {
        assertThat(RepositoryAssetClassifier.classify("AGENTS.md", "markdown"))
                .isEqualTo(RepositoryAssetType.RULE);
        assertThat(RepositoryAssetClassifier.classify("docs/tasks/gate.md", "markdown"))
                .isEqualTo(RepositoryAssetType.TASK);
        assertThat(RepositoryAssetClassifier.classify("deploy/application.yml", "yaml"))
                .isEqualTo(RepositoryAssetType.CONFIG);
        assertThat(RepositoryAssetClassifier.classify("docs/architecture.md", "markdown"))
                .isEqualTo(RepositoryAssetType.DOCUMENT);
        assertThat(RepositoryAssetClassifier.classify("src/Main.java", "java"))
                .isEqualTo(RepositoryAssetType.CODE);
    }

    @Test
    void recognizesAssetsThatBelongOnProjectOverview() {
        assertThat(
                        RepositoryAssetClassifier.isKeyAsset(
                                "README.md", RepositoryAssetType.DOCUMENT))
                .isTrue();
        assertThat(
                        RepositoryAssetClassifier.isKeyAsset(
                                ".github/instructions/java.md", RepositoryAssetType.RULE))
                .isTrue();
        assertThat(
                        RepositoryAssetClassifier.isKeyAsset(
                                "src/Main.java", RepositoryAssetType.CODE))
                .isFalse();
    }
}
