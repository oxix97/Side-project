package org.stockwellness.application.port.in.stock.result;

import java.math.BigDecimal;

/**
 * 수익률 계산 결과 API 응답용 DTO
 */
public record ReturnRateResponse(
        String ticker,
        String currency,
        String period,
        BigDecimal stockReturnRate,
        BigDecimal benchmarkReturnRate
) {
}
