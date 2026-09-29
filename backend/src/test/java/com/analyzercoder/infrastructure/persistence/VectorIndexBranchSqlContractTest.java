package com.analyzercoder.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VectorIndexBranchSqlContractTest {
    @Test
    void currentVectorIndexReadsUseTheExactBranchContentVersion() throws Exception {
        String mapper = resource("mappers/VectorIndexQueryMapper.xml");

        assertThat(mapper)
                .contains("<select id=\"summaryForBranch\"")
                .contains("<select id=\"chunksForBranch\"")
                .contains("<select id=\"knowledgeForBranch\"")
                .contains("c.branch_id=#{branchId}")
                .contains("c.content_version=#{contentVersion}")
                .contains("knowledge_applies_to_branch(k.id,#{branchId},k.revision)")
                .contains("v.branch_id=#{branchId}")
                .contains("v.content_version=#{contentVersion}");
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
