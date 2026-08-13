package org.stockwellness.adapter.batch.stockprice.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.stereotype.Component;
import org.stockwellness.adapter.out.kafka.batch.KafkaEventPublisher;
import org.stockwellness.domain.stock.price.StockPrice;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockPriceSyncEventListener implements ItemWriteListener<List<StockPrice>>, JobExecutionListener {

    private final KafkaEventPublisher kafkaEventPublisher;
    private final Set<String> updatedSymbols = ConcurrentHashMap.newKeySet();

    @Override
    public void afterWrite(Chunk<? extends List<StockPrice>> items) {
        for (List<StockPrice> prices : items) {
            if (prices == null) continue;
            for (StockPrice stockPrice : prices) {
                if (stockPrice != null && stockPrice.getStock() != null) {
                    updatedSymbols.add(stockPrice.getStock().getTicker());
                }
            }
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String publishEvent = jobExecution.getJobParameters().getString("publishEvent", "false");
        
        if (!"true".equalsIgnoreCase(publishEvent)) {
            log.info(">>> 이번 잡 실행에서는 카프카 이벤트 발행이 비활성화되었습니다 (publishEvent=false).");
            updatedSymbols.clear();
            return;
        }

        if (jobExecution.getStatus().isUnsuccessful()) {
            log.warn("!!! [ERROR] 종목 시세 동기화 잡 실패. 이벤트 발행을 건너뜁니다.");
            updatedSymbols.clear();
            return;
        }

        if (!updatedSymbols.isEmpty()) {
            List<String> symbols = new ArrayList<>(updatedSymbols);
            log.info("종목 시세 동기화 잡 완료. {}개 종목에 대한 이벤트를 발행합니다.", symbols.size());
            kafkaEventPublisher.publishStockPriceUpdated(symbols);
            updatedSymbols.clear();
        } else {
            log.info("종목 시세 동기화 잡 완료. 업데이트된 종목이 없어 이벤트를 발행하지 않습니다.");
        }
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        updatedSymbols.clear();
    }
}
