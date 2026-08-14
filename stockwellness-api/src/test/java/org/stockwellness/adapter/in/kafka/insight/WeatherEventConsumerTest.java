package org.stockwellness.adapter.in.kafka.insight;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.RetryableTopic;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.WeatherInsightService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class WeatherEventConsumerTest {

    @Mock
    private WeatherInsightService weatherInsightService;

    @Test
    @DisplayName("AI 인사이트 생성 실패를 삼키지 않아 Kafka 재시도 경로로 전달한다")
    void consume_RethrowsProcessingFailure() {
        MarketScoreCalculatedEvent event = new MarketScoreCalculatedEvent(
                LocalDate.of(2026, 8, 12),
                "KOSPI",
                75,
                List.of()
        );
        IllegalStateException failure = new IllegalStateException("AI provider unavailable");
        doThrow(failure).when(weatherInsightService).generateInsights(event);
        WeatherEventConsumer consumer = new WeatherEventConsumer(weatherInsightService);

        assertThatThrownBy(() -> consumer.consume(event))
                .isSameAs(failure);
    }

    @Test
    @DisplayName("시장 점수 이벤트는 1초와 2초 간격으로 총 3회 처리한 뒤 DLT로 보낸다")
    void retryPolicyUsesThreeAttemptsAndDlt() throws Exception {
        RetryableTopic retryableTopic = WeatherEventConsumer.class
                .getMethod("consume", MarketScoreCalculatedEvent.class)
                .getAnnotation(RetryableTopic.class);

        assertThat(retryableTopic).isNotNull();
        assertThat(retryableTopic.attempts()).isEqualTo("3");
        assertThat(retryableTopic.backoff().delay()).isEqualTo(1_000L);
        assertThat(retryableTopic.backoff().multiplier()).isEqualTo(2.0);
        assertThat(retryableTopic.kafkaTemplate()).isEqualTo("kafkaTemplate");
        assertThat(retryableTopic.dltTopicSuffix()).isEqualTo("-dlt");
        assertThat(WeatherEventConsumer.class
                .getMethod("handleDlt", MarketScoreCalculatedEvent.class)
                .isAnnotationPresent(DltHandler.class))
                .isTrue();
    }
}
