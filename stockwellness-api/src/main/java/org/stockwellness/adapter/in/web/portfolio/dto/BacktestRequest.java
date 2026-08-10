package org.stockwellness.adapter.in.web.portfolio.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.stockwellness.domain.portfolio.RebalancingPeriod;

/**
 * 백테스트 시뮬레이션 요청 DTO
 */
public record BacktestRequest(
    /**
     * 투자 전략 (LUMP_SUM: 거치식, DCA: 적립식)
     */
    @NotBlank String strategy, 
    
    /**
     * 투자 금액 (거치식은 총액, 적립식은 월 입금액)
     */
    @NotNull @Positive BigDecimal amount,

    /**
     * 성과 비교 기준이 될 대표 벤치마크 지수 티커 (예: KOSPI, SPY)
     */
    @JsonAlias("benchmarkTicker") String primaryBenchmark,

    /**
     * 시뮬레이션 기간 (1M, 3M, 6M, 1Y, 3Y, ALL)
     */
    String period,

    /**
     * 배당금 재투자 여부
     */
    Boolean dividendReinvested,

    /**
     * 자산 재배분(리밸런싱) 주기 (NONE, MONTHLY, QUARTERLY, YEARLY)
     */
    RebalancingPeriod rebalancingPeriod,
    
    /**
     * 시뮬레이션 시 적용할 종목별 비중 (%) (비어있으면 현재 포트폴리오 비중 사용)
     */
    Map<String, BigDecimal> weights
) {
    /** 레거시 코드에서 사용하던 accessor를 유지합니다. */
    public String benchmarkTicker() {
        return primaryBenchmark;
    }
}
