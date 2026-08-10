package org.stockwellness.application.port.in.portfolio.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.stockwellness.application.port.in.portfolio.result.PriceStatus;
import org.stockwellness.domain.portfolio.AssetType;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.stock.price.StockPrice;

public record PortfolioItemResponse(
    String symbol,
    String name,
    BigDecimal quantity,
    BigDecimal purchasePrice,
    BigDecimal currentPrice,
    String currency,
    AssetType assetType,
    BigDecimal purchaseAmount,
    BigDecimal currentValue,
    BigDecimal returnRate,
    BigDecimal targetWeight,
    PriceStatus priceStatus,
    LocalDate priceAsOfDate
) {
    public static PortfolioItemResponse from(PortfolioItem entity, String name, BigDecimal currentPrice) {
        return from(entity, name, currentPrice,
                currentPrice == null ? PriceStatus.MISSING : PriceStatus.AVAILABLE, null);
    }

    public static PortfolioItemResponse from(
            PortfolioItem entity,
            String name,
            StockPrice stockPrice,
            LocalDate portfolioAsOfDate
    ) {
        if (entity.getAssetType() == AssetType.CASH) {
            return from(entity, name, BigDecimal.ONE, PriceStatus.AVAILABLE, null);
        }

        if (stockPrice == null || stockPrice.getClosePrice() == null) {
            return from(entity, name, null, PriceStatus.MISSING, null);
        }

        LocalDate priceAsOfDate = stockPrice.getId() == null ? null : stockPrice.getId().getBaseDate();
        PriceStatus priceStatus = priceAsOfDate != null
                && portfolioAsOfDate != null
                && priceAsOfDate.isBefore(portfolioAsOfDate)
                ? PriceStatus.STALE
                : PriceStatus.AVAILABLE;
        return from(entity, name, stockPrice.getClosePrice(), priceStatus, priceAsOfDate);
    }

    private static PortfolioItemResponse from(
            PortfolioItem entity,
            String name,
            BigDecimal currentPrice,
            PriceStatus priceStatus,
            LocalDate priceAsOfDate
    ) {
        BigDecimal purchaseAmount = entity.calculatePurchaseAmount();
        BigDecimal currentValue = currentPrice == null ? null : currentPrice.multiply(entity.getQuantity());
        BigDecimal returnRate = null;

        if (currentValue != null && purchaseAmount.compareTo(BigDecimal.ZERO) > 0) {
            returnRate = currentValue.subtract(purchaseAmount)
                    .divide(purchaseAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else if (currentValue != null) {
            returnRate = BigDecimal.ZERO;
        }

        return new PortfolioItemResponse(
            entity.getSymbol(),
            name,
            entity.getQuantity(),
            entity.getPurchasePrice(),
            currentPrice,
            entity.getCurrency(),
            entity.getAssetType(),
            purchaseAmount,
            currentValue,
            returnRate,
            entity.getTargetWeight(),
            priceStatus,
            priceAsOfDate
        );
    }
}
