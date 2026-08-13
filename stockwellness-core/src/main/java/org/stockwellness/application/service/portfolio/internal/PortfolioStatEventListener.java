package org.stockwellness.application.service.portfolio.internal;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.service.portfolio.PortfolioStatBatchService;
import org.stockwellness.domain.portfolio.event.PortfolioUpdatedEvent;

/**
 * 포트폴리오 변경 이벤트를 구독하여 실시간으로 통계 지표를 재계산하는 리스너입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioStatEventListener {

    private final PortfolioStatBatchService portfolioStatBatchService;
    private final PortfolioPort portfolioPort;

    /**
     * 포트폴리오 변경 트랜잭션이 성공적으로 완료된 후, 
     * 비동기로 통계 업데이트를 수행합니다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePortfolioUpdated(PortfolioUpdatedEvent event) {
        log.info("[이벤트] 포트폴리오 {} 변경 감지 - 통계 재계산 시작", event.portfolioId());
        
        try {
            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(event.portfolioId()));
            log.info("[이벤트] 포트폴리오 {} 통계 업데이트 완료", event.portfolioId());
        } catch (Exception e) {
            log.error("[이벤트] 포트폴리오 {} 통계 업데이트 실패: {}", event.portfolioId(), e.getMessage());
        }
    }
}
