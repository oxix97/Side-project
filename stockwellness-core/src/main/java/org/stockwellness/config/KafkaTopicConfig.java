package org.stockwellness.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String STOCK_PRICE_UPDATED_TOPIC = "stock-price-updated";
    public static final String PORTFOLIO_ANALYSIS_COMPLETED_TOPIC = "portfolio-analysis-completed";
    public static final String MARKET_SCORE_CALCULATED_TOPIC = "market-score-calculated";
    public static final String MARKET_SCORE_CALCULATED_DLT_TOPIC = "market-score-calculated-dlt";

    @Bean
    public NewTopic stockPriceUpdatedTopic() {
        return TopicBuilder.name(STOCK_PRICE_UPDATED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic portfolioAnalysisCompletedTopic() {
        return TopicBuilder.name(PORTFOLIO_ANALYSIS_COMPLETED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
