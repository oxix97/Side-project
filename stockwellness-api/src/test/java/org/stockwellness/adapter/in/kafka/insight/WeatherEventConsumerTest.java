package org.stockwellness.adapter.in.kafka.insight;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.WeatherInsightService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
