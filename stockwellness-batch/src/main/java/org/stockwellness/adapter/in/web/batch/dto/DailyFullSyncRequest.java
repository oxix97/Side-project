package org.stockwellness.adapter.in.web.batch.dto;

public record DailyFullSyncRequest(
        /** 동기화 기준일 (yyyyMMdd) */
        String endDate
) {
}
