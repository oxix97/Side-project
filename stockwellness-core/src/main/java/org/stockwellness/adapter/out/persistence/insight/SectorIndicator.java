package org.stockwellness.adapter.out.persistence.insight;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.stockwellness.domain.shared.AbstractEntity;

@Getter
@Entity
@Table(name = "sector_indicator")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SectorIndicator extends AbstractEntity {

    @Column(nullable = false)
    private LocalDate baseDate;

    @Column(nullable = false, length = 20)
    private String sectorCode;

    @Column(name = "ma20", precision = 10, scale = 4)
    private BigDecimal ma20Disparity;

    @Column(precision = 10, scale = 4)
    private BigDecimal rsi14;

    @Column(precision = 10, scale = 4)
    private BigDecimal adr;

    private boolean isOverheated = false;

    @Builder
    public SectorIndicator(LocalDate baseDate, String sectorCode, BigDecimal ma20Disparity, BigDecimal rsi14, BigDecimal adr, boolean isOverheated) {
        this.baseDate = baseDate;
        this.sectorCode = sectorCode;
        this.ma20Disparity = ma20Disparity;
        this.rsi14 = rsi14;
        this.adr = adr;
        this.isOverheated = isOverheated;
    }

    public void update(BigDecimal ma20Disparity, BigDecimal rsi14, BigDecimal adr, boolean isOverheated) {
        this.ma20Disparity = ma20Disparity;
        this.rsi14 = rsi14;
        this.adr = adr;
        this.isOverheated = isOverheated;
    }
}
