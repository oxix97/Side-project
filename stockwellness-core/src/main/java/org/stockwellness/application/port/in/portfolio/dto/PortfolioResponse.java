package org.stockwellness.application.port.in.portfolio.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.stockwellness.application.port.in.portfolio.result.PriceStatus;
import org.stockwellness.application.port.in.portfolio.result.ValuationStatus;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.price.StockPrice;

public record PortfolioResponse(
        Long id,
        String name,
        String description,
        BigDecimal totalPurchaseAmount,
        BigDecimal currentTotalValue,
        BigDecimal totalReturnRate,
        List<PortfolioItemResponse> items,
        ValuationStatus valuationStatus,
        LocalDate asOfDate,
        List<String> missingSymbols
) {
    public PortfolioResponse(
            Long id,
            String name,
            String description,
            BigDecimal totalPurchaseAmount,
            BigDecimal currentTotalValue,
            BigDecimal totalReturnRate,
            List<PortfolioItemResponse> items
    ) {
        this(id, name, description, totalPurchaseAmount, currentTotalValue, totalReturnRate,
                items, ValuationStatus.COMPLETE, null, List.of());
    }

    public static PortfolioResponse from(Portfolio entity, Map<String, BigDecimal> latestPriceMap, Map<String, Stock> stockMap) {
        return fromLegacyPriceMap(entity, latestPriceMap, stockMap);
    }

    public static PortfolioResponse fromPriceHistories(
            Portfolio entity,
            Map<String, List<StockPrice>> priceHistories,
            Map<String, Stock> stockMap
    ) {
        Map<String, List<StockPrice>> safeHistories = priceHistories == null ? Map.of() : priceHistories;
        LocalDate asOfDate = safeHistories.values().stream()
                .flatMap(List::stream)
                .filter(price -> price != null && price.getClosePrice() != null && price.getId() != null)
                .map(price -> price.getId().getBaseDate())
                .filter(date -> date != null && !date.isAfter(LocalDate.now()))
                .max(LocalDate::compareTo)
                .orElse(null);

        List<PortfolioItemResponse> itemResponses = entity.getItems().stream()
                .map(item -> {
                    StockPrice latestPrice = safeHistories.getOrDefault(item.getSymbol(), List.of()).stream()
                            .filter(price -> price != null && price.getClosePrice() != null)
                            .filter(price -> price.getId() == null || price.getId().getBaseDate() == null
                                    || !price.getId().getBaseDate().isAfter(LocalDate.now()))
                            .findFirst()
                            .orElse(null);
                    return PortfolioItemResponse.from(item, resolveDisplayName(item, stockMap), latestPrice, asOfDate);
                })
                .toList();

        BigDecimal totalPurchaseAmount = entity.calculateTotalPurchaseAmount();
        List<String> missingSymbols = itemResponses.stream()
                .filter(item -> item.assetType().name().equals("STOCK"))
                .filter(item -> item.priceStatus() == PriceStatus.MISSING)
                .map(PortfolioItemResponse::symbol)
                .toList();
        boolean hasStock = itemResponses.stream().anyMatch(item -> item.assetType().name().equals("STOCK"));
        boolean hasAvailablePrice = itemResponses.stream()
                .filter(item -> item.assetType().name().equals("STOCK"))
                .anyMatch(item -> item.priceStatus() != PriceStatus.MISSING);
        ValuationStatus valuationStatus = !hasStock || missingSymbols.isEmpty()
                ? ValuationStatus.COMPLETE
                : hasAvailablePrice ? ValuationStatus.PARTIAL : ValuationStatus.UNAVAILABLE;

        BigDecimal currentTotalValue = valuationStatus == ValuationStatus.COMPLETE
                ? itemResponses.stream().map(PortfolioItemResponse::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        BigDecimal totalReturnRate = null;

        if (currentTotalValue != null && totalPurchaseAmount.compareTo(BigDecimal.ZERO) > 0) {
            totalReturnRate = currentTotalValue.subtract(totalPurchaseAmount)
                    .divide(totalPurchaseAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else if (currentTotalValue != null) {
            totalReturnRate = BigDecimal.ZERO;
        }

        return new PortfolioResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                totalPurchaseAmount,
                currentTotalValue,
                totalReturnRate,
                itemResponses,
                valuationStatus,
                asOfDate,
                missingSymbols
        );
    }

    private static PortfolioResponse fromLegacyPriceMap(
            Portfolio entity,
            Map<String, BigDecimal> latestPriceMap,
            Map<String, Stock> stockMap
    ) {
        Map<String, BigDecimal> prices = latestPriceMap == null ? Map.of() : latestPriceMap;
        List<PortfolioItemResponse> itemResponses = entity.getItems().stream()
                .map(item -> PortfolioItemResponse.from(item, resolveDisplayName(item, stockMap), prices.get(item.getSymbol())))
                .toList();
        BigDecimal totalPurchaseAmount = entity.calculateTotalPurchaseAmount();
        List<String> missingSymbols = itemResponses.stream()
                .filter(item -> item.assetType().name().equals("STOCK"))
                .filter(item -> item.priceStatus() == PriceStatus.MISSING)
                .map(PortfolioItemResponse::symbol)
                .toList();
        boolean hasStock = itemResponses.stream().anyMatch(item -> item.assetType().name().equals("STOCK"));
        boolean hasAvailablePrice = itemResponses.stream()
                .filter(item -> item.assetType().name().equals("STOCK"))
                .anyMatch(item -> item.priceStatus() != PriceStatus.MISSING);
        ValuationStatus status = !hasStock || missingSymbols.isEmpty()
                ? ValuationStatus.COMPLETE
                : hasAvailablePrice ? ValuationStatus.PARTIAL : ValuationStatus.UNAVAILABLE;
        BigDecimal currentTotalValue = status == ValuationStatus.COMPLETE
                ? itemResponses.stream().map(PortfolioItemResponse::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        BigDecimal totalReturnRate = currentTotalValue == null ? null
                : totalPurchaseAmount.compareTo(BigDecimal.ZERO) > 0
                ? currentTotalValue.subtract(totalPurchaseAmount).divide(totalPurchaseAmount, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        return new PortfolioResponse(entity.getId(), entity.getName(), entity.getDescription(),
                totalPurchaseAmount, currentTotalValue, totalReturnRate, itemResponses,
                status, null, missingSymbols);
    }

    private static String resolveDisplayName(PortfolioItem item, Map<String, Stock> stockMap) {
        if (item.getAssetType().name().equals("CASH")) {
            return "현금";
        }

        Stock stock = stockMap.get(item.getSymbol());
        if (stock != null && stock.getName() != null && !stock.getName().isBlank()) {
            return stock.getName();
        }
        return item.getSymbol();
    }
}
