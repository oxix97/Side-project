package org.stockwellness.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.stockwellness.domain.portfolio.exception.InvalidPortfolioException;
import org.stockwellness.domain.shared.AbstractEntity;
import org.stockwellness.global.util.FinanceCalculationUtil;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

/**
 * 포트폴리오를 구성하는 개별 자산 항목(종목 또는 현금)을 나타내는 도메인 모델입니다.
 * 종목 코드, 자산 타입, 수량, 매수 단가 및 목표 비중 정보를 관리합니다.
 */
@Slf4j
@ToString
@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
public class PortfolioItem extends AbstractEntity {

    /**
     * 해당 항목이 속한 포트폴리오
     */
    @JsonIgnore
    @ToString.Exclude
    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "portfolio_id")
    private Portfolio portfolio;

    /**
     * 종목 코드 (주식인 경우 Ticker, 현금인 경우 'CASH')
     */
    @Column(nullable = false)
    private String symbol;

    /**
     * 자산 타입 (주식, 현금 등)
     */
    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    /**
     * 보유 수량 (현금인 경우 금액)
     */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    /**
     * 매수 평균 단가 (현금인 경우 1)
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal purchasePrice;

    /**
     * 통화 단위 (KRW, USD 등)
     */
    @Column(nullable = false)
    private String currency;

    /**
     * 포트폴리오 내에서 이 자산이 차지해야 할 목표 비중 (%)
     */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal targetWeight = BigDecimal.ZERO;

    /**
     * 자산 매수일
     */
    @Column(nullable = false)
    private LocalDate purchaseDate;

    /**
     * 주식 종목 항목을 생성합니다. (목표 비중 0%)
     */
    public static PortfolioItem createStock(String symbol, BigDecimal quantity, BigDecimal purchasePrice, String currency) {
        return createStock(symbol, quantity, purchasePrice, currency, BigDecimal.ZERO, LocalDate.now());
    }

    /**
     * 목표 비중 및 매수일을 포함한 주식 종목 항목을 생성합니다.
     *
     * @param symbol 종목 코드
     * @param quantity 수량
     * @param purchasePrice 매수 단가
     * @param currency 통화
     * @param targetWeight 목표 비중 (%)
     * @param purchaseDate 매수일
     * @return 생성된 PortfolioItem 객체
     */
    public static PortfolioItem createStock(String symbol, BigDecimal quantity, BigDecimal purchasePrice, String currency, BigDecimal targetWeight, LocalDate purchaseDate) {
        validateQuantity(quantity);
        validatePrice(purchasePrice);
        validateWeight(targetWeight);
        PortfolioItem item = new PortfolioItem();
        item.symbol = symbol;
        item.assetType = AssetType.STOCK;
        item.quantity = quantity;
        item.purchasePrice = purchasePrice;
        item.currency = currency;
        item.targetWeight = targetWeight;
        item.purchaseDate = (purchaseDate != null) ? purchaseDate : LocalDate.now();
        return item;
    }

    /**
     * EOD 종가와 목표 비중으로 생성된 가상 포트폴리오 종목을 생성합니다.
     */
    public static PortfolioItem createSimulatedStock(
            String symbol,
            BigDecimal quantity,
            BigDecimal latestEodClose,
            String currency,
            BigDecimal targetWeight,
            LocalDate asOfDate
    ) {
        return createStock(symbol, quantity, latestEodClose, currency, targetWeight, asOfDate);
    }

    /**
     * 현금 자산 항목을 생성합니다. (목표 비중 0%)
     */
    public static PortfolioItem createCash(BigDecimal amount, String currency) {
        return createCash(amount, currency, BigDecimal.ZERO, LocalDate.now());
    }

    /**
     * 목표 비중 및 매수일을 포함한 현금 자산 항목을 생성합니다.
     *
     * @param amount 현금 금액
     * @param currency 통화
     * @param targetWeight 목표 비중 (%)
     * @param purchaseDate 매수일
     * @return 생성된 PortfolioItem 객체
     */
    public static PortfolioItem createCash(BigDecimal amount, String currency, BigDecimal targetWeight, LocalDate purchaseDate) {
        validateQuantity(amount);
        validateWeight(targetWeight);
        PortfolioItem item = new PortfolioItem();
        item.symbol = "CASH";
        item.assetType = AssetType.CASH;
        item.quantity = amount;
        item.purchasePrice = BigDecimal.ONE; // 현금의 단가는 1로 고정
        item.currency = currency;
        item.targetWeight = targetWeight;
        item.purchaseDate = (purchaseDate != null) ? purchaseDate : LocalDate.now();
        return item;
    }

    /**
     * 수량/금액의 유효성을 검사합니다. (0보다 커야 함)
     */
    private static void validateQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPortfolioException();
        }
    }

    /**
     * 가격의 유효성을 검사합니다. (0 이상이어야 함)
     */
    private static void validatePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidPortfolioException();
        }
    }

    /**
     * 목표 비중의 유효성을 검사합니다. (0% ~ 100% 사이)
     */
    private static void validateWeight(BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(BigDecimal.valueOf(100)) > 0
                || weight.scale() > 4) {
            throw new InvalidPortfolioException();
        }
    }

    /**
     * 항목을 특정 포트폴리오에 할당합니다.
     */
    protected void assignPortfolio(Portfolio portfolio) {
        this.portfolio = portfolio;
    }

    /**
     * 이 항목의 총 매수 금액을 계산합니다. (수량 * 매수단가)
     *
     * @return 총 매수 금액
     */
    public BigDecimal calculatePurchaseAmount() {
        return quantity.multiply(purchasePrice);
    }

    /**
     * 현재가를 기반으로 해당 종목의 손익 금액을 계산합니다.
     *
     * @param currentPrice 현재가
     * @return 평가 손익 금액 (평가액 - 매수금액)
     */
    public BigDecimal calculateProfitLoss(BigDecimal currentPrice) {
        if (currentPrice == null) return BigDecimal.ZERO;
        BigDecimal currentValue = (assetType == AssetType.CASH) ? quantity : quantity.multiply(currentPrice);
        return currentValue.subtract(calculatePurchaseAmount());
    }

    /**
     * 현재가를 기반으로 해당 종목의 수익률(%)을 계산합니다.
     *
     * @param currentPrice 현재가
     * @return 수익률 (%)
     */
    public BigDecimal calculateReturnRate(BigDecimal currentPrice) {
        BigDecimal purchaseAmount = calculatePurchaseAmount();
        if (purchaseAmount.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        
        BigDecimal profitLoss = calculateProfitLoss(currentPrice);
        return FinanceCalculationUtil.calculateRate(profitLoss, purchaseAmount);
    }

    /**
     * 현재가를 기반으로 해당 종목이 전체 투자금 대비 기여한 수익률(%)을 계산합니다.
     *
     * @param currentPrice 현재가
     * @param totalInvestment 전체 포트폴리오 총 투자금
     * @return 기여 수익률 (%)
     */
    public BigDecimal calculateContribution(BigDecimal currentPrice, BigDecimal totalInvestment) {
        if (totalInvestment == null || totalInvestment.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        
        BigDecimal profitLoss = calculateProfitLoss(currentPrice);
        return FinanceCalculationUtil.calculateRate(profitLoss, totalInvestment);
    }
}
