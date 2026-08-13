package org.stockwellness.adapter.in.kafka;

import java.util.List;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.adapter.out.persistence.portfolio.PortfolioAdapter;
import org.stockwellness.domain.stock.event.StockPriceUpdatedEvent;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.stockwellness.config.KafkaTopicConfig.STOCK_PRICE_UPDATED_TOPIC;
import static org.stockwellness.domain.common.cache.CacheType.AI_ANALYSIS;
import static org.stockwellness.domain.common.cache.CacheType.MARKET_BREADTH;
import static org.stockwellness.domain.common.cache.CacheType.MARKET_DASHBOARD;
import static org.stockwellness.domain.common.cache.CacheType.SECTOR_RANKING;
import static org.stockwellness.domain.common.cache.CacheType.SECTOR_SUPPLY;
import static org.stockwellness.domain.common.cache.CacheType.STOCK_SUPPLY_RANKING;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {STOCK_PRICE_UPDATED_TOPIC},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class StockPriceUpdateConsumerTest {

    private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private CacheManager cacheManager;

    @MockitoBean
    private PortfolioAdapter portfolioAdapter;

    @Test
    void testConsumeStockPriceUpdatedEvent() {
        // given
        List<String> symbols = List.of("AAPL", "TSLA");
        StockPriceUpdatedEvent event = StockPriceUpdatedEvent.of(symbols);
        List<Long> portfolioIds = List.of(1L, 2L);
        
        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache(anyString())).thenReturn(mockCache);
        when(portfolioAdapter.findPortfolioIdsBySymbols(symbols)).thenReturn(portfolioIds);

        MessageListenerContainer stockPriceListener = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                .filter(messageListenerContainer -> {
                    String[] topics = messageListenerContainer.getContainerProperties().getTopics();
                    return topics != null && List.of(topics).contains(STOCK_PRICE_UPDATED_TOPIC);
                })
                .findFirst()
                .orElseThrow();

        await()
                .atMost(ASSIGNMENT_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> stockPriceListener.getAssignedPartitions().size()
                        == embeddedKafkaBroker.getPartitionsPerTopic());

        // when
        kafkaTemplate.send(STOCK_PRICE_UPDATED_TOPIC, event);

        // then
        await()
            .atMost(ASSIGNMENT_TIMEOUT)
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                verify(cacheManager).getCache(SECTOR_RANKING.getCacheName());
                verify(cacheManager).getCache(SECTOR_SUPPLY.getCacheName());
                verify(cacheManager).getCache(MARKET_DASHBOARD.getCacheName());
                verify(cacheManager).getCache(MARKET_BREADTH.getCacheName());
                verify(cacheManager).getCache(STOCK_SUPPLY_RANKING.getCacheName());
                verify(cacheManager).getCache(AI_ANALYSIS.getCacheName());
                verify(mockCache, atLeastOnce()).clear();
                verify(mockCache, atLeastOnce()).evict(anyLong());
            });
    }
}
