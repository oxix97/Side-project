package org.stockwellness.adapter.batch.sector.listener;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;
import org.stockwellness.application.port.in.stock.SectorInsightUseCase;

@Slf4j
@Component
@RequiredArgsConstructor
public class SectorEodJobListener implements JobExecutionListener {

    private final SectorInsightUseCase sectorInsightUseCase;

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info("SectorEodJob 성공적으로 완료. 캐시 프리워밍을 시작합니다.");
            
            // 1. 캐시 프리워밍 (상위 10개 기준)
            // 지우지 않고 덮어쓰기(Warm-up) 방식을 사용하여 서비스 중단 없는 안전한 갱신 구현
            LocalDate today = LocalDate.now();
            try {
                sectorInsightUseCase.getTopSectorsByFluctuation(today, null, 10);
                log.info("섹터 캐시 프리워밍 완료.");
            } catch (Exception e) {
                log.error("!!! [ERROR] 캐시 프리워밍 중 오류 발생", e);
            }
        }
    }
}
