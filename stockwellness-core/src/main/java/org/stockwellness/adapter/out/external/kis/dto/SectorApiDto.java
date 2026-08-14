package org.stockwellness.adapter.out.external.kis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SectorApiDto(
        String sectorCode,
        String sectorName,
        LocalDate baseDate,
        BigDecimal sectorIndexCurrentPrice,
        BigDecimal avgFluctuationRate,
        BigDecimal advanceRatio,
        Long netForeignBuyAmount,
        Long netInstBuyAmount
) {
    public SectorApiDto(
            String sectorCode,
            String sectorName,
            LocalDate baseDate,
            BigDecimal sectorIndexCurrentPrice,
            BigDecimal avgFluctuationRate,
            Long netForeignBuyAmount,
            Long netInstBuyAmount
    ) {
        this(
                sectorCode,
                sectorName,
                baseDate,
                sectorIndexCurrentPrice,
                avgFluctuationRate,
                BigDecimal.valueOf(100),
                netForeignBuyAmount,
                netInstBuyAmount
        );
    }
}
