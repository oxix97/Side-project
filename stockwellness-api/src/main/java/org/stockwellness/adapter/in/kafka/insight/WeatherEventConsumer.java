package org.stockwellness.adapter.in.kafka.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.WeatherInsightService;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherEventConsumer {

    private final WeatherInsightService weatherInsightService;

    @KafkaListener(topics = "market-score-calculated", groupId = "${spring.kafka.consumer.group-id:stockwellness-insight-group}")
    public void consume(MarketScoreCalculatedEvent event) {
        log.info("📥 Received MarketScoreCalculatedEvent for {} on {}", event.marketType(), event.baseDate());
        weatherInsightService.generateInsights(event);
    }
}
