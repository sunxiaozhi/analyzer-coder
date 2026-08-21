package com.analyzercoder.application.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeDependencyDetectorTest {

    @Test
    void detectsResourcesWithoutLeakingCredentialsOrUrlPaths() {
        List<RuntimeDependencyDetector.DetectedResource> resources =
                RuntimeDependencyDetector.detect(
                        """
                        datasource: jdbc:postgresql://user:top-secret@db.internal:5432/orders?ssl=true
                        callback: http://partner.internal:8080/private/callback?token=secret
                        local: http://127.0.0.1:8080/actuator/health
                        spring.kafka.bootstrap-servers: kafka.internal:9092
                        """);

        assertThat(resources)
                .filteredOn(resource -> "POSTGRESQL".equals(resource.type()))
                .singleElement()
                .satisfies(
                        resource -> {
                            assertThat(resource.locator()).isEqualTo("db.internal:5432");
                            assertThat(resource.id()).doesNotContain("top-secret", "orders");
                        });
        assertThat(resources)
                .filteredOn(
                        resource ->
                                "HTTP_API".equals(resource.type())
                                        && "partner.internal:8080".equals(resource.locator()))
                .singleElement()
                .extracting(RuntimeDependencyDetector.DetectedResource::insecure)
                .isEqualTo(true);
        assertThat(resources)
                .filteredOn(
                        resource ->
                                "HTTP_API".equals(resource.type())
                                        && "127.0.0.1:8080".equals(resource.locator()))
                .singleElement()
                .extracting(RuntimeDependencyDetector.DetectedResource::insecure)
                .isEqualTo(false);
        assertThat(resources)
                .extracting(RuntimeDependencyDetector.DetectedResource::type)
                .contains("KAFKA");
    }
}
