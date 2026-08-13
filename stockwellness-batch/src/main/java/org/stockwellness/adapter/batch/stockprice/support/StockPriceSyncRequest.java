package org.stockwellness.adapter.batch.stockprice.support;

public record StockPriceSyncRequest(
        /** 특정 종목 티커 (단건 처리 시 사용) */
        String targetTicker,
        /** 수집 시작일 (yyyyMMdd) */
        String startDate,
        /** 수집 종료일 (yyyyMMdd) */
        String endDate
) {
}
