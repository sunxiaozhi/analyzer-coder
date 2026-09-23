package com.analyzercoder.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocalVectorCompatibilityTest {
    @Test
    void preservesExistingVectorsWithoutSubstringAllocations() {
        for (String content : List.of("", "a", "Ab", "你好😀", "function foo() { return bar(); }")) {
            assertEquals(previousVector(content), IntelligenceService.localVector(content));
        }
    }

    private static String previousVector(String text) {
        float[] output = new float[64];
        String normalized = text.toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            int hash =
                    normalized
                            .substring(index, Math.min(normalized.length(), index + 3))
                            .hashCode();
            output[Math.floorMod(hash, 64)] += (hash & 1) == 0 ? 1 : -1;
        }
        double norm = 0;
        for (float number : output) {
            norm += number * number;
        }
        norm = Math.sqrt(norm);
        StringBuilder vector = new StringBuilder("[");
        for (int index = 0; index < output.length; index++) {
            if (index > 0) {
                vector.append(',');
            }
            vector.append(norm == 0 ? 0 : output[index] / norm);
        }
        return vector.append(']').toString();
    }
}
