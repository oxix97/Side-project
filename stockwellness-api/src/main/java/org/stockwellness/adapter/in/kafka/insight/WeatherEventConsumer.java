package org.stockwellness.adapter.in.kafka.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.WeatherInsightService;
import org.stockwellness.config.KafkaTopicConfig;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherEventConsumer {

    private final WeatherInsightService weatherInsightService;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1_000, multiplier = 2.0),
            kafkaTemplate = "kafkaTemplate",
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
            topics = KafkaTopicConfig.MARKET_SCORE_CALCULATED_TOPIC,
            groupId = "${spring.kafka.consumer.group-id:stockwellness-insight-group}"
    )
    public void consume(MarketScoreCalculatedEvent event) {
        log.info("📥 Received MarketScoreCalculatedEvent for {} on {}", event.marketType(), event.baseDate());
        weatherInsightService.generateInsights(event);
    }

    @DltHandler
    public void handleDlt(MarketScoreCalculatedEvent event) {
        log.error(
                "시장 날씨 AI 처리 최종 실패 baseDate={}, marketType={}",
                event.baseDate(),
                event.marketType()
        );
    }
}
