package org.stockwellness.adapter.in.kafka.insight;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.WeatherInsightService;
import org.stockwellness.config.KafkaTopicConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@ActiveProfiles("test")
@DirtiesContext
@EmbeddedKafka(
        partitions = 1,
        topics = {
                KafkaTopicConfig.MARKET_SCORE_CALCULATED_TOPIC,
                KafkaTopicConfig.MARKET_SCORE_CALCULATED_DLT_TOPIC
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class WeatherEventConsumerRetryIntegrationTest {

    private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private WeatherInsightService weatherInsightService;

    @Test
    void failedEventIsProcessedThreeTimesThenPublishedToDltWithOriginalKeyAndValue() throws Exception {
        MarketScoreCalculatedEvent event = new MarketScoreCalculatedEvent(
                LocalDate.of(2026, 8, 14),
                "KOSPI",
                70,
                List.of(new MarketScoreCalculatedEvent.SectorScore("001", "전기전자", 80))
        );
        String eventKey = "KOSPI:2026-08-14";
        doThrow(new IllegalStateException("provider unavailable"))
                .when(weatherInsightService).generateInsights(event);

        MessageListenerContainer mainListener = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                .filter(container -> containsTopic(container, KafkaTopicConfig.MARKET_SCORE_CALCULATED_TOPIC))
                .findFirst()
                .orElseThrow();
        await()
                .atMost(ASSIGNMENT_TIMEOUT)
                .until(() -> mainListener.getAssignedPartitions().size()
                        == embeddedKafkaBroker.getPartitionsPerTopic());

        BlockingQueue<ConsumerRecord<String, MarketScoreCalculatedEvent>> dltRecords = new LinkedBlockingQueue<>();
        KafkaMessageListenerContainer<String, MarketScoreCalculatedEvent> dltContainer = dltContainer(dltRecords);
        dltContainer.start();
        ContainerTestUtils.waitForAssignment(dltContainer, embeddedKafkaBroker.getPartitionsPerTopic());

        try {
            kafkaTemplate.send(KafkaTopicConfig.MARKET_SCORE_CALCULATED_TOPIC, eventKey, event)
                    .get(10, TimeUnit.SECONDS);

            verify(weatherInsightService, timeout(12_000).times(3)).generateInsights(event);
            ConsumerRecord<String, MarketScoreCalculatedEvent> dltRecord = dltRecords.poll(12, TimeUnit.SECONDS);

            assertThat(dltRecord).isNotNull();
            assertThat(dltRecord.key()).isEqualTo(eventKey);
            assertThat(dltRecord.value()).isEqualTo(event);
        } finally {
            dltContainer.stop();
        }
    }

    private boolean containsTopic(MessageListenerContainer container, String topic) {
        String[] topics = container.getContainerProperties().getTopics();
        return topics != null && List.of(topics).contains(topic);
    }

    private KafkaMessageListenerContainer<String, MarketScoreCalculatedEvent> dltContainer(
            BlockingQueue<ConsumerRecord<String, MarketScoreCalculatedEvent>> records
    ) {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "market-weather-dlt-assertion",
                "true",
                embeddedKafkaBroker
        );
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        DefaultKafkaConsumerFactory<String, MarketScoreCalculatedEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(
                        consumerProps,
                        new StringDeserializer(),
                        new JsonDeserializer<>(MarketScoreCalculatedEvent.class, false)
                );
        ContainerProperties properties = new ContainerProperties(KafkaTopicConfig.MARKET_SCORE_CALCULATED_DLT_TOPIC);
        KafkaMessageListenerContainer<String, MarketScoreCalculatedEvent> container =
                new KafkaMessageListenerContainer<>(consumerFactory, properties);
        container.setupMessageListener((MessageListener<String, MarketScoreCalculatedEvent>) records::add);
        return container;
    }
}
