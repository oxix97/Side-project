package org.stockwellness.adapter.in.web.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Real E2E 격리 증명 profile")
class E2eIsolationAttestationProfileTest {

    @Test
    @DisplayName("prod profile에서는 격리 증명 엔드포인트 bean을 노출하지 않는다")
    void controller_is_not_exposed_in_prod() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod", "e2e");
            context.register(E2eIsolationAttestationController.class);
            context.refresh();

            assertThat(context.getBeansOfType(E2eIsolationAttestationController.class)).isEmpty();
        }
    }
}
