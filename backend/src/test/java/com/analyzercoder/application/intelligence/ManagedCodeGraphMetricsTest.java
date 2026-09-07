package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ManagedCodeGraphMetricsTest {
    @Test
    void readsFormattedCountsAndRealZeroEdges() {
        assertThat(ManagedCodeGraphService.metric("Indexed 6,523 nodes and 12,559 edges", "nodes"))
                .isEqualTo(6523);
        assertThat(ManagedCodeGraphService.metric("Indexed 3 nodes and 0 edges", "edges")).isZero();
    }

    @Test
    void refusesToSubstituteZeroForAnUnrecognizedCliResponse() {
        assertThatThrownBy(() -> ManagedCodeGraphService.metric("Initialization finished", "nodes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法读取 CodeGraph");
    }
}
