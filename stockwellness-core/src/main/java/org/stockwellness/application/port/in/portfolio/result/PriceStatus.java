package org.stockwellness.application.port.in.portfolio.result;

/**
 * 종목 평가에 사용한 EOD 종가의 신뢰 상태.
 */
public enum PriceStatus {
    AVAILABLE,
    STALE,
    MISSING
}
