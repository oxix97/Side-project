package org.stockwellness.application.service.stock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.stockwellness.adapter.out.external.kis.dto.SectorApiDto;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.analysis.TechnicalCalculator;
import org.stockwellness.domain.stock.analysis.TechnicalIndicatorCalculator;
import org.stockwellness.domain.stock.insight.LeadingStock;
import org.stockwellness.domain.stock.insight.MarketIndex;
import org.stockwellness.domain.stock.insight.SectorIndicators;
import org.stockwellness.domain.stock.insight.SectorInsight;
import org.stockwellness.domain.stock.insight.SectorTechnicalIndicators;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.domain.stock.price.TechnicalIndicators;

@Slf4j
@Service
public class SectorAnalysisService {

    public SectorInsight analyze(
            MarketIndex index, 
            SectorApiDto currentData, 
            SectorInsight yesterdayData, 
            List<BigDecimal> pastPrices,
            List<StockPrice> sectorStockPrices
    ) {
        String sectorCode = index.getIndexCode();
        
        // 1. 수급 지표 연산 (연속 매수 일수)
        int foreignConsecutiveDays = calculateConsecutiveDays(
                currentData.netForeignBuyAmount(),
                yesterdayData != null ? yesterdayData.getForeignConsecutiveBuyDays() : 0
        );
        int instConsecutiveDays = calculateConsecutiveDays(
                currentData.netInstBuyAmount(),
                yesterdayData != null ? yesterdayData.getInstConsecutiveBuyDays() : 0
        );

        // 2. 기술적 지표 연산
        TechnicalIndicators calculated;
        boolean isOverheated = false;

        if (pastPrices == null || pastPrices.isEmpty()) {
            log.warn("섹터 {}의 과거 시세 데이터가 없어 지표 계산을 건너뜁니다.", sectorCode);
            calculated = TechnicalIndicators.empty();
        } else {
            List<BigDecimal> closingPricesForQuant = new ArrayList<>(pastPrices);
            Collections.reverse(closingPricesForQuant); 
            
            closingPricesForQuant.add(currentData.sectorIndexCurrentPrice());
            calculated = TechnicalIndicatorCalculator.calculateLatest(closingPricesForQuant);
            
            isOverheated = TechnicalCalculator.isOverheated(
                    currentData.sectorIndexCurrentPrice(),
                    calculated.getMa20(),
                    calculated.getRsi14()
            );
        }

        // 3. 주도주 산출 (거래대금 상위 5개, 상승 종목 우선)
        List<LeadingStock> leadingStocks = calculateLeadingStocks(sectorStockPrices);
        if (leadingStocks.isEmpty() && yesterdayData != null && yesterdayData.getLeadingStocks() != null) {
            log.info("섹터 {}의 당일 주도주가 없어 전일 데이터를 승계합니다.", sectorCode);
            leadingStocks = new ArrayList<>(yesterdayData.getLeadingStocks());
        }

        // 4. SectorIndicators 생성
        SectorIndicators indicators = SectorIndicators.of(
                currentData.sectorIndexCurrentPrice(),
                currentData.avgFluctuationRate(),
                currentData.advanceRatio(),
                currentData.netForeignBuyAmount(),
                currentData.netInstBuyAmount(),
                foreignConsecutiveDays,
                instConsecutiveDays
        );

        // 5. 최종 Entity 생성
        SectorInsight insight = SectorInsight.of(
                currentData.sectorName(),
                sectorCode,
                resolveMarketType(sectorCode),
                currentData.baseDate(),
                indicators,
                SectorTechnicalIndicators.from(calculated),
                isOverheated
        );
        insight.updateLeadingStocks(leadingStocks);
        
        return insight;
    }

    /**
     * 섹터 주도주 산출 로직
     */
    private List<LeadingStock> calculateLeadingStocks(List<StockPrice> stockPrices) {
        if (stockPrices == null || stockPrices.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 상승 종목(등락률 > 0) 필터링
        List<StockPrice> gainers = stockPrices.stream()
                .filter(p -> p.getFluctuationRate().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(StockPrice::getTransactionAmt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .toList();

        // 2. 만약 상승 종목이 없다면, 거래대금이 가장 큰 종목 5개 추출
        List<StockPrice> finalSelection = gainers;
        if (gainers.isEmpty()) {
            finalSelection = stockPrices.stream()
                    .sorted(Comparator.comparing(StockPrice::getTransactionAmt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(5)
                    .toList();
        }

        // 3. LeadingStock DTO로 변환
        return finalSelection.stream()
                .map(LeadingStock::from)
                .collect(Collectors.toList());
    }

    private int calculateConsecutiveDays(Long todayNetBuyAmount, int yesterdayConsecutiveDays) {
        if (todayNetBuyAmount != null && todayNetBuyAmount > 0) {
            return yesterdayConsecutiveDays + 1;
        }
        return 0;
    }

    private MarketType resolveMarketType(String indexCode) {
        if (indexCode != null && (indexCode.startsWith("0") || indexCode.startsWith("2"))) return MarketType.KOSPI;
        if (indexCode != null && indexCode.startsWith("1")) return MarketType.KOSDAQ;
        return MarketType.KOSPI;
    }
}
