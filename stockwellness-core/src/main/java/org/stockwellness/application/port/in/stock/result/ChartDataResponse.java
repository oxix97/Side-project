package org.stockwellness.application.port.in.stock.result;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 차트 데이터 API 응답용 DTO
 */
public record ChartDataResponse(
        String ticker,
        String currency,
        String stockName,
        String benchmarkName,
        List<ChartPoint> prices,
        List<BenchmarkPoint> benchmarks
) {
    public record ChartPoint(
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal adjClose,
            Long volume,
            BigDecimal transactionAmt,
            BigDecimal ma5,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma120
    ) {
    }

    public record BenchmarkPoint(
            LocalDate date,
            BigDecimal returnRate
    ) {
    }
}
