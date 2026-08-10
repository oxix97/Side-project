package org.stockwellness.integration.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTestProfileIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Test
    void test_profile_keeps_kafka_listener_containers_stopped() {
        assertThat(kafkaListenerEndpointRegistry.getListenerContainers())
                .allSatisfy(listenerContainer -> assertThat(listenerContainer.isRunning()).isFalse());
    }
}
