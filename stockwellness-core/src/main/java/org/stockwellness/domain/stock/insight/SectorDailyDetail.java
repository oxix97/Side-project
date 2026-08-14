package org.stockwellness.domain.stock.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.stockwellness.application.port.out.stock.SectorDailyDetailSnapshot;
import org.stockwellness.domain.shared.AbstractEntity;

/**
 * KIS 업종 API에서 수집한 원천 일별 스냅샷.
 *
 * <p>이 엔티티는 배치 수집 결과를 보존하는 저장소 역할만 담당한다.
 * 사용자 응답에 필요한 분석/판단 결과는 {@link SectorInsight}에만 둔다.
 */
@Getter
@Entity
@Table(
        name = "sector_daily_detail",
        uniqueConstraints = {@UniqueConstraint(name = "uk_sector_daily_detail", columnNames = {"sector_code", "base_date"})},
        indexes = {@Index(name = "idx_sector_daily_detail_base_date", columnList = "base_date")}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SectorDailyDetail extends AbstractEntity {

    /**
     * 도메인에서 사용하는 섹터 코드.
     * 원천값은 {@code market_index.index_code}에서 유입된다.
     */
    @Column(name = "sector_code", nullable = false, length = 10)
    private String sectorCode;

    @Column(name = "sector_name", nullable = false, length = 100)
    private String sectorName;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "current_price", precision = 19, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "change_amount", precision = 19, scale = 4)
    private BigDecimal changeAmount;

    @Column(name = "change_sign", length = 4)
    private String changeSign;

    @Column(name = "change_rate", precision = 10, scale = 4)
    private BigDecimal changeRate;

    @Column(name = "accumulated_volume")
    private Long accumulatedVolume;

    @Column(name = "previous_volume")
    private Long previousVolume;

    @Column(name = "accumulated_trading_amount")
    private Long accumulatedTradingAmount;

    @Column(name = "previous_trading_amount")
    private Long previousTradingAmount;

    @Column(name = "open_price", precision = 19, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 19, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 19, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "rising_issue_count")
    private Integer risingIssueCount;

    @Column(name = "upper_limit_issue_count")
    private Integer upperLimitIssueCount;

    @Column(name = "steady_issue_count")
    private Integer steadyIssueCount;

    @Column(name = "falling_issue_count")
    private Integer fallingIssueCount;

    @Column(name = "lower_limit_issue_count")
    private Integer lowerLimitIssueCount;

    @Column(name = "yearly_high_price", precision = 19, scale = 4)
    private BigDecimal yearlyHighPrice;

    @Column(name = "yearly_high_rate", precision = 10, scale = 4)
    private BigDecimal yearlyHighRate;

    @Column(name = "yearly_high_date")
    private LocalDate yearlyHighDate;

    @Column(name = "yearly_low_price", precision = 19, scale = 4)
    private BigDecimal yearlyLowPrice;

    @Column(name = "yearly_low_rate", precision = 10, scale = 4)
    private BigDecimal yearlyLowRate;

    @Column(name = "yearly_low_date")
    private LocalDate yearlyLowDate;

    @Column(name = "total_ask_residual_quantity")
    private Long totalAskResidualQuantity;

    @Column(name = "total_bid_residual_quantity")
    private Long totalBidResidualQuantity;

    @Column(name = "sell_residual_rate", precision = 10, scale = 4)
    private BigDecimal sellResidualRate;

    @Column(name = "buy_residual_rate", precision = 10, scale = 4)
    private BigDecimal buyResidualRate;

    @Column(name = "net_buy_residual_quantity")
    private Long netBuyResidualQuantity;

    @Column(name = "net_foreign_buy_amount")
    private Long netForeignBuyAmount;

    @Column(name = "net_inst_buy_amount")
    private Long netInstBuyAmount;

    public static SectorDailyDetail of(String sectorCode, String sectorName, SectorDailyDetailSnapshot snapshot) {
        SectorDailyDetail entity = new SectorDailyDetail();
        entity.sectorCode = sectorCode;
        entity.sectorName = sectorName;
        entity.apply(snapshot);
        return entity;
    }

    public void update(String sectorName, SectorDailyDetailSnapshot snapshot) {
        this.sectorName = sectorName;
        apply(snapshot);
    }

    public BigDecimal calculateAdvanceDeclineRatio() {
        if (risingIssueCount == null
                || upperLimitIssueCount == null
                || fallingIssueCount == null
                || lowerLimitIssueCount == null) {
            throw new IllegalStateException(
                    "ADR 원천 종목 수가 누락되었습니다: " + baseDate + ", " + sectorCode
            );
        }

        int advancing = risingIssueCount + upperLimitIssueCount;
        int declining = fallingIssueCount + lowerLimitIssueCount;
        if (advancing == 0 && declining == 0) {
            return BigDecimal.valueOf(100);
        }
        return BigDecimal.valueOf(advancing)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(Math.max(declining, 1)), 2, RoundingMode.HALF_UP);
    }

    private void apply(SectorDailyDetailSnapshot snapshot) {
        this.baseDate = snapshot.baseDate();
        this.currentPrice = snapshot.currentPrice();
        this.changeAmount = snapshot.changeAmount();
        this.changeSign = snapshot.changeSign();
        this.changeRate = snapshot.changeRate();
        this.accumulatedVolume = snapshot.accumulatedVolume();
        this.previousVolume = snapshot.previousVolume();
        this.accumulatedTradingAmount = snapshot.accumulatedTradingAmount();
        this.previousTradingAmount = snapshot.previousTradingAmount();
        this.openPrice = snapshot.openPrice();
        this.highPrice = snapshot.highPrice();
        this.lowPrice = snapshot.lowPrice();
        this.risingIssueCount = snapshot.risingIssueCount();
        this.upperLimitIssueCount = snapshot.upperLimitIssueCount();
        this.steadyIssueCount = snapshot.steadyIssueCount();
        this.fallingIssueCount = snapshot.fallingIssueCount();
        this.lowerLimitIssueCount = snapshot.lowerLimitIssueCount();
        this.yearlyHighPrice = snapshot.yearlyHighPrice();
        this.yearlyHighRate = snapshot.yearlyHighRate();
        this.yearlyHighDate = snapshot.yearlyHighDate();
        this.yearlyLowPrice = snapshot.yearlyLowPrice();
        this.yearlyLowRate = snapshot.yearlyLowRate();
        this.yearlyLowDate = snapshot.yearlyLowDate();
        this.totalAskResidualQuantity = snapshot.totalAskResidualQuantity();
        this.totalBidResidualQuantity = snapshot.totalBidResidualQuantity();
        this.sellResidualRate = snapshot.sellResidualRate();
        this.buyResidualRate = snapshot.buyResidualRate();
        this.netBuyResidualQuantity = snapshot.netBuyResidualQuantity();
        this.netForeignBuyAmount = snapshot.netForeignBuyAmount();
        this.netInstBuyAmount = snapshot.netInstBuyAmount();
    }
}
