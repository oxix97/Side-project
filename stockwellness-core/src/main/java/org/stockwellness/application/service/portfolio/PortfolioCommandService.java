package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.application.port.in.portfolio.ManagePortfolioUseCase;
import org.stockwellness.application.port.in.portfolio.command.CreatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.CreateSimulatedPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.UpdatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.result.CreateSimulatedPortfolioResult;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.portfolio.AssetType;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.event.PortfolioUpdatedEvent;
import org.stockwellness.domain.portfolio.exception.DuplicatePortfolioNameException;
import org.stockwellness.domain.portfolio.exception.PortfolioAccessDeniedException;
import org.stockwellness.domain.portfolio.exception.PortfolioDomainException;
import org.stockwellness.domain.portfolio.exception.PortfolioNotFoundException;
import org.stockwellness.domain.stock.Currency;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.exception.InvalidStockCodeException;
import org.stockwellness.domain.stock.exception.StockPriceException;
import org.stockwellness.domain.stock.price.StockPrice;
import static org.stockwellness.global.error.ErrorCode.INVALID_INPUT_VALUE;
import static org.stockwellness.global.error.ErrorCode.PRICE_DATA_NOT_FOUND;
import static org.stockwellness.global.error.ErrorCode.UNSUPPORTED_PORTFOLIO_CURRENCY;

@Service
@RequiredArgsConstructor
@Transactional
public class PortfolioCommandService implements ManagePortfolioUseCase {

    private final PortfolioPort portfolioPort;
    private final StockPort stockPort;
    private final StockPricePort stockPricePort;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Long createPortfolio(CreatePortfolioCommand command) {
        if (portfolioPort.existsPortfolioName(command.memberId(), command.name())) {
            throw new DuplicatePortfolioNameException();
        }

        Portfolio portfolio = Portfolio.create(command.memberId(), command.name(), command.description());

        List<PortfolioItem> items = command.items().stream()
                .map(this::mapToEntity)
                .toList();

        portfolio.updateItems(items);

        Portfolio saved = portfolioPort.savePortfolio(portfolio);
        eventPublisher.publishEvent(new PortfolioUpdatedEvent(command.memberId(), saved.getId()));
        return saved.getId();
    }

    @Override
    public CreateSimulatedPortfolioResult createSimulatedPortfolio(CreateSimulatedPortfolioCommand command) {
        validateSimulatedCommand(command);

        if (portfolioPort.existsPortfolioName(command.memberId(), command.name())) {
            throw new DuplicatePortfolioNameException();
        }

        List<SimulatedItem> simulatedItems = command.items().stream()
                .map(item -> createSimulatedItem(command.totalAmount(), item))
                .toList();
        LocalDate asOfDate = commonAsOfDate(simulatedItems);

        Portfolio portfolio = Portfolio.create(command.memberId(), command.name(), command.description());
        portfolio.updateItems(simulatedItems.stream().map(SimulatedItem::portfolioItem).toList());

        Portfolio saved = portfolioPort.savePortfolio(portfolio);
        eventPublisher.publishEvent(new PortfolioUpdatedEvent(command.memberId(), saved.getId()));
        return new CreateSimulatedPortfolioResult(saved.getId(), asOfDate);
    }

    @Override
    public void updatePortfolio(UpdatePortfolioCommand command) {
        Portfolio portfolio = loadOwnedPortfolio(command.portfolioId(), command.memberId());

        // 1. 기본 정보 수정 (이름 변경 시 중복 체크)
        if (!portfolio.getName().equals(command.name())) {
            if (portfolioPort.existsPortfolioName(command.memberId(), command.name())) {
                throw new DuplicatePortfolioNameException();
            }
        }
        portfolio.updateBasicInfo(command.name(), command.description());

        // 2. 구성 종목 수정
        List<PortfolioItem> newItems = command.items().stream()
                .map(this::mapToEntity)
                .toList();

        portfolio.updateItems(newItems);
        
        eventPublisher.publishEvent(new PortfolioUpdatedEvent(command.memberId(), command.portfolioId()));
    }

    @Override
    public void deletePortfolio(Long memberId, Long portfolioId) {
        loadOwnedPortfolio(portfolioId, memberId);
        portfolioPort.deletePortfolio(portfolioId);
    }

    private Portfolio loadOwnedPortfolio(Long portfolioId, Long memberId) {
        return portfolioPort.loadPortfolio(portfolioId, memberId)
                .orElseThrow(() -> {
                    if (portfolioPort.findById(portfolioId).isPresent()) {
                        throw new PortfolioAccessDeniedException();
                    }
                    return new PortfolioNotFoundException();
                });
    }

    private PortfolioItem mapToEntity(CreatePortfolioCommand.PortfolioItemCommand item) {
        if (item.assetType() == AssetType.STOCK) {
            if (!stockPort.existsByTicker(item.symbol())) {
                throw new InvalidStockCodeException("Stock not found with symbol: " + item.symbol());
            }
            return PortfolioItem.createStock(item.symbol(), item.quantity(), item.purchasePrice(), item.currency(), item.targetWeight(), LocalDate.now());
        } else {
            return PortfolioItem.createCash(item.quantity(), item.currency(), item.targetWeight(), LocalDate.now());
        }
    }

    private void validateSimulatedCommand(CreateSimulatedPortfolioCommand command) {
        if (command.totalAmount() == null || command.totalAmount().compareTo(BigDecimal.ZERO) <= 0
                || command.items() == null || command.items().isEmpty()
                || command.items().stream().anyMatch(item -> item == null || item.targetWeight() == null
                || item.targetWeight().compareTo(BigDecimal.ZERO) <= 0
                || item.targetWeight().compareTo(BigDecimal.valueOf(100)) > 0
                || item.targetWeight().scale() > 4)) {
            throw new PortfolioDomainException(INVALID_INPUT_VALUE);
        }

        BigDecimal totalWeight = command.items().stream()
                .map(CreateSimulatedPortfolioCommand.ItemCommand::targetWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new PortfolioDomainException(INVALID_INPUT_VALUE);
        }
    }

    private SimulatedItem createSimulatedItem(
            BigDecimal totalAmount,
            CreateSimulatedPortfolioCommand.ItemCommand itemCommand
    ) {
        if (itemCommand.targetWeight() == null || itemCommand.targetWeight().compareTo(BigDecimal.ZERO) <= 0
                || itemCommand.targetWeight().compareTo(BigDecimal.valueOf(100)) > 0
                || itemCommand.targetWeight().scale() > 4) {
            throw new PortfolioDomainException(INVALID_INPUT_VALUE);
        }

        Stock stock = stockPort.loadStockByTicker(itemCommand.symbol())
                .orElseThrow(() -> new InvalidStockCodeException("Stock not found with symbol: " + itemCommand.symbol()));
        if (stock.getCurrency() != Currency.KRW) {
            throw new PortfolioDomainException(UNSUPPORTED_PORTFOLIO_CURRENCY);
        }

        StockPrice latestPrice = stockPricePort.findLatestByTicker(itemCommand.symbol())
                .filter(price -> price.getClosePrice() != null && price.getClosePrice().compareTo(BigDecimal.ZERO) > 0)
                .orElseThrow(() -> new StockPriceException(PRICE_DATA_NOT_FOUND));
        BigDecimal allocatedAmount = totalAmount.multiply(itemCommand.targetWeight())
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_DOWN);
        BigDecimal quantity = allocatedAmount.divide(latestPrice.getClosePrice(), 6, RoundingMode.HALF_DOWN);

        PortfolioItem portfolioItem = PortfolioItem.createSimulatedStock(
                stock.getTicker(),
                quantity,
                latestPrice.getClosePrice(),
                stock.getCurrency().name(),
                itemCommand.targetWeight(),
                latestPrice.getId().getBaseDate());
        return new SimulatedItem(portfolioItem, latestPrice.getId().getBaseDate());
    }

    private LocalDate commonAsOfDate(List<SimulatedItem> items) {
        LocalDate asOfDate = items.getFirst().asOfDate();
        boolean sameAsOfDate = items.stream().allMatch(item -> item.asOfDate().equals(asOfDate));
        if (!sameAsOfDate) {
            throw new StockPriceException(PRICE_DATA_NOT_FOUND);
        }
        return asOfDate;
    }

    private record SimulatedItem(PortfolioItem portfolioItem, LocalDate asOfDate) {
    }

    private PortfolioItem mapToEntity(UpdatePortfolioCommand.PortfolioItemCommand item) {
        if (item.assetType() == AssetType.STOCK) {
            if (!stockPort.existsByTicker(item.symbol())) {
                throw new InvalidStockCodeException("Stock not found with symbol: " + item.symbol());
            }
            return PortfolioItem.createStock(item.symbol(), item.quantity(), item.purchasePrice(), item.currency(), item.targetWeight(), LocalDate.now());
        } else {
            return PortfolioItem.createCash(item.quantity(), item.currency(), item.targetWeight(), LocalDate.now());
        }
    }
}
