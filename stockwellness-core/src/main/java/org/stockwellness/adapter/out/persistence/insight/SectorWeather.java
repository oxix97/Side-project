package org.stockwellness.adapter.out.persistence.insight;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.stockwellness.domain.shared.AbstractEntity;

@Getter
@Entity
@Table(
        name = "sector_weather",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sector_weather_base_date_code",
                columnNames = {"base_date", "sector_code"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SectorWeather extends AbstractEntity {

    @Column(nullable = false)
    private LocalDate baseDate;

    @Column(nullable = false, length = 20)
    private String sectorCode;

    @Column(nullable = false)
    private int weatherScore;

    @Column(nullable = false, length = 20)
    private String weatherState;

    @Column(length = 200)
    private String aiTitle;

    @Column(columnDefinition = "TEXT")
    private String aiInsight;

    @Builder
    public SectorWeather(LocalDate baseDate, String sectorCode, int weatherScore, String weatherState, String aiTitle, String aiInsight) {
        this.baseDate = baseDate;
        this.sectorCode = sectorCode;
        this.weatherScore = weatherScore;
        this.weatherState = weatherState;
        this.aiTitle = aiTitle;
        this.aiInsight = aiInsight;
    }

    public void updateScore(int weatherScore, String weatherState) {
        this.weatherScore = weatherScore;
        this.weatherState = weatherState;
    }

    public void updateInsight(String aiTitle, String aiInsight) {
        this.aiTitle = aiTitle;
        this.aiInsight = aiInsight;
    }
}
