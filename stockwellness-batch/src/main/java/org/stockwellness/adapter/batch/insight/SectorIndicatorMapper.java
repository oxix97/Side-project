package org.stockwellness.adapter.batch.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.domain.stock.insight.SectorInsight;

final class SectorIndicatorMapper {

    private SectorIndicatorMapper() {
    }

    static SectorIndicator from(SectorInsight insight) {
        var indicators = insight.getIndicators();
        return from(insight, indicators == null ? null : indicators.getAdvanceRatio());
    }

    static SectorIndicator from(SectorInsight insight, BigDecimal advanceDeclineRatio) {
        var indicators = insight.getIndicators();
        var technical = insight.getTechnicalIndicators();
        if (indicators == null
                || indicators.getSectorIndexCurrentPrice() == null
                || indicators.getSectorIndexCurrentPrice().compareTo(BigDecimal.ZERO) <= 0
                || advanceDeclineRatio == null
                || technical == null
                || technical.getMa20() == null
                || technical.getMa20().compareTo(BigDecimal.ZERO) <= 0
                || technical.getRsi14() == null) {
            throw new IllegalStateException(
                    "시장 날씨 필수 원천 지표가 누락되었습니다: "
                            + insight.getBaseDate() + ", " + insight.getSectorCode()
            );
        }

        BigDecimal disparity = indicators.getSectorIndexCurrentPrice()
                .divide(technical.getMa20(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return SectorIndicator.builder()
                .baseDate(insight.getBaseDate())
                .sectorCode(insight.getSectorCode())
                .ma20Disparity(disparity)
                .adr(advanceDeclineRatio)
                .rsi14(technical.getRsi14())
                .isOverheated(insight.isOverheated())
                .build();
    }
}
