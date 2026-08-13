package org.stockwellness.adapter.out.persistence.insight;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.stockwellness.domain.shared.AbstractEntity;

@Getter
@Entity
@Table(name = "market_weather")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketWeather extends AbstractEntity {

    @Column(nullable = false)
    private LocalDate baseDate;

    @Column(nullable = false, length = 20)
    private String marketType;

    @Column(nullable = false)
    private int weatherScore;

    @Column(nullable = false, length = 20)
    private String weatherState;

    @Column(columnDefinition = "TEXT")
    private String aiSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<SectorSummary> topSectors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<SectorSummary> bottomSectors;

    @Builder
    public MarketWeather(LocalDate baseDate, String marketType, int weatherScore, String weatherState, String aiSummary, List<SectorSummary> topSectors, List<SectorSummary> bottomSectors) {
        this.baseDate = baseDate;
        this.marketType = marketType;
        this.weatherScore = weatherScore;
        this.weatherState = weatherState;
        this.aiSummary = aiSummary;
        this.topSectors = topSectors;
        this.bottomSectors = bottomSectors;
    }

    public void updateCalculation(
            int weatherScore,
            String weatherState,
            List<SectorSummary> topSectors,
            List<SectorSummary> bottomSectors
    ) {
        this.weatherScore = weatherScore;
        this.weatherState = weatherState;
        this.topSectors = topSectors;
        this.bottomSectors = bottomSectors;
    }

    public void updateInsight(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public record SectorSummary(
        String code,
        String name,
        int score,
        String emoji
    ) {}
}
