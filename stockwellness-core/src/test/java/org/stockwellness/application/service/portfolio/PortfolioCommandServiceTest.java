package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.stockwellness.application.port.in.portfolio.command.CreatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.CreateSimulatedPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.UpdatePortfolioCommand;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.portfolio.AssetType;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.exception.DuplicatePortfolioNameException;
import org.stockwellness.domain.portfolio.exception.PortfolioAccessDeniedException;
import org.stockwellness.domain.portfolio.exception.PortfolioNotFoundException;
import org.stockwellness.domain.stock.exception.InvalidStockCodeException;
import org.stockwellness.domain.stock.Currency;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.StockStatus;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.global.error.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PortfolioCommandServiceTest {

    @InjectMocks
    private PortfolioCommandService portfolioCommandService;

    @Mock
    private PortfolioPort portfolioPort;

    @Mock
    private StockPort stockPort;

    @Mock
    private StockPricePort stockPricePort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static final Long MEMBER_ID = 1L;
    private static final Long PORTFOLIO_ID = 100L;

    @Test
    @DisplayName("포트폴리오 생성: 중복되지 않은 이름과 유효한 종목으로 생성에 성공한다")
    void createPortfolio_Success() {
        // given
        CreatePortfolioCommand.PortfolioItemCommand itemCommand = 
                new CreatePortfolioCommand.PortfolioItemCommand("005930", BigDecimal.TEN, BigDecimal.valueOf(50000), "KRW", AssetType.STOCK, BigDecimal.valueOf(100));
        CreatePortfolioCommand command = new CreatePortfolioCommand(MEMBER_ID, "내 포트폴리오", "설명", List.of(itemCommand));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "내 포트폴리오")).willReturn(false);
        given(stockPort.existsByTicker("005930")).willReturn(true);
        given(portfolioPort.savePortfolio(any(Portfolio.class))).willAnswer(invocation -> {
            Portfolio portfolio = invocation.getArgument(0);
            ReflectionTestUtils.setField(portfolio, "id", PORTFOLIO_ID);
            return portfolio;
        });

        // when
        Long savedId = portfolioCommandService.createPortfolio(command);

        // then
        assertThat(savedId).isEqualTo(PORTFOLIO_ID);
        verify(portfolioPort).savePortfolio(any(Portfolio.class));
    }

    @Test
    @DisplayName("포트폴리오 생성 실패: 이미 존재하는 포트폴리오 이름인 경우 예외가 발생한다")
    void createPortfolio_DuplicateName() {
        // given
        CreatePortfolioCommand command = new CreatePortfolioCommand(MEMBER_ID, "중복이름", "설명", List.of());
        given(portfolioPort.existsPortfolioName(MEMBER_ID, "중복이름")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> portfolioCommandService.createPortfolio(command))
                .isInstanceOf(DuplicatePortfolioNameException.class);
    }

    @Test
    @DisplayName("포트폴리오 생성 실패: 존재하지 않는 종목 코드가 포함된 경우 예외가 발생한다")
    void createPortfolio_InvalidStock() {
        // given
        CreatePortfolioCommand.PortfolioItemCommand itemCommand = 
                new CreatePortfolioCommand.PortfolioItemCommand("INVALID", BigDecimal.TEN, BigDecimal.valueOf(50000), "KRW", AssetType.STOCK, BigDecimal.valueOf(100));
        CreatePortfolioCommand command = new CreatePortfolioCommand(MEMBER_ID, "포트", "설명", List.of(itemCommand));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "포트")).willReturn(false);
        given(stockPort.existsByTicker("INVALID")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> portfolioCommandService.createPortfolio(command))
                .isInstanceOf(InvalidStockCodeException.class);
    }

    @Test
    @DisplayName("가상 포트폴리오 생성: 1천만원을 60/40 비중으로 최신 EOD 종가에 따라 양수 수량으로 배분한다")
    void createSimulatedPortfolio_allocatesKrwAmountByLatestEodClose() {
        Stock samsung = stock("005930", Currency.KRW);
        Stock skHynix = stock("000660", Currency.KRW);
        LocalDate asOfDate = LocalDate.of(2026, 8, 7);
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID,
                "가상 포트폴리오",
                "장기 투자",
                new BigDecimal("10000000"),
                List.of(
                        new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("60.0")),
                        new CreateSimulatedPortfolioCommand.ItemCommand("000660", new BigDecimal("40.0"))));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "가상 포트폴리오")).willReturn(false);
        given(stockPort.loadStockByTicker("005930")).willReturn(Optional.of(samsung));
        given(stockPort.loadStockByTicker("000660")).willReturn(Optional.of(skHynix));
        given(stockPricePort.findLatestByTicker("005930"))
                .willReturn(Optional.of(price(samsung, asOfDate, new BigDecimal("50000"))));
        given(stockPricePort.findLatestByTicker("000660"))
                .willReturn(Optional.of(price(skHynix, asOfDate, new BigDecimal("200000"))));
        given(portfolioPort.savePortfolio(any(Portfolio.class))).willAnswer(invocation -> {
            Portfolio portfolio = invocation.getArgument(0);
            ReflectionTestUtils.setField(portfolio, "id", PORTFOLIO_ID);
            return portfolio;
        });

        var result = portfolioCommandService.createSimulatedPortfolio(command);

        assertThat(result.portfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(result.asOfDate()).isEqualTo(asOfDate);
        var savedPortfolio = mockingDetails(portfolioPort).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("savePortfolio"))
                .findFirst()
                .map(invocation -> (Portfolio) invocation.getArgument(0))
                .orElseThrow();
        assertThat(savedPortfolio.getItems())
                .extracting(PortfolioItem::getSymbol, PortfolioItem::getQuantity, PortfolioItem::getPurchasePrice, PortfolioItem::getCurrency)
                .containsExactlyInAnyOrder(
                        tuple("005930", new BigDecimal("120.000000"), new BigDecimal("50000"), "KRW"),
                        tuple("000660", new BigDecimal("20.000000"), new BigDecimal("200000"), "KRW"));
        assertThat(savedPortfolio.getItems()).allSatisfy(item -> assertThat(item.getQuantity()).isPositive());
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 투자금은 0보다 커야 한다")
    void createSimulatedPortfolio_rejectsNonPositiveTotalAmount() {
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, BigDecimal.ZERO,
                List.of(new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("100"))));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("G001");
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 목표 비중 합계는 정확히 100이어야 한다")
    void createSimulatedPortfolio_rejectsWeightSumOtherThanOneHundred() {
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, new BigDecimal("10000000"),
                List.of(new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("99.9"))));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("G001");
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 누락된 목표 비중은 원시 예외 없이 입력 오류로 거부한다")
    void createSimulatedPortfolio_rejectsMissingTargetWeight() {
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, new BigDecimal("10000000"),
                List.of(new CreateSimulatedPortfolioCommand.ItemCommand("005930", null)));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("G001");
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: DB 정밀도를 넘는 목표 비중은 반올림하지 않고 G001로 거부한다")
    void createSimulatedPortfolio_rejectsTargetWeightWithMoreThanFourDecimalPlaces() {
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, new BigDecimal("10000000"),
                List.of(
                        new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("50.00001")),
                        new CreateSimulatedPortfolioCommand.ItemCommand("000660", new BigDecimal("49.99999"))));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("G001");
        verify(stockPort, never()).loadStockByTicker(any());
        verify(portfolioPort, never()).savePortfolio(any());
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 최신 EOD 종가가 없으면 S002를 반환한다")
    void createSimulatedPortfolio_rejectsMissingEodPrice() {
        Stock samsung = stock("005930", Currency.KRW);
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, new BigDecimal("10000000"),
                List.of(new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("100"))));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "가상 포트폴리오")).willReturn(false);
        given(stockPort.loadStockByTicker("005930")).willReturn(Optional.of(samsung));
        given(stockPricePort.findLatestByTicker("005930")).willReturn(Optional.empty());

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("S002");
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 종목별 최신 EOD 기준일이 다르면 S002로 거부하고 저장하지 않는다")
    void createSimulatedPortfolio_rejectsDifferentLatestEodDates() {
        Stock samsung = stock("005930", Currency.KRW);
        Stock skHynix = stock("000660", Currency.KRW);
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID,
                "가상 포트폴리오",
                null,
                new BigDecimal("10000000"),
                List.of(
                        new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("60")),
                        new CreateSimulatedPortfolioCommand.ItemCommand("000660", new BigDecimal("40"))));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "가상 포트폴리오")).willReturn(false);
        given(stockPort.loadStockByTicker("005930")).willReturn(Optional.of(samsung));
        given(stockPort.loadStockByTicker("000660")).willReturn(Optional.of(skHynix));
        given(stockPricePort.findLatestByTicker("005930"))
                .willReturn(Optional.of(price(samsung, LocalDate.of(2026, 8, 7), new BigDecimal("50000"))));
        given(stockPricePort.findLatestByTicker("000660"))
                .willReturn(Optional.of(price(skHynix, LocalDate.of(2026, 8, 6), new BigDecimal("200000"))));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("S002");
        verify(portfolioPort, never()).savePortfolio(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 종목 마스터 통화가 KRW가 아니면 P006을 반환한다")
    void createSimulatedPortfolio_rejectsNonKrwStockFromMasterCurrency() {
        Stock apple = stock("AAPL", Currency.USD);
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, new BigDecimal("10000000"),
                List.of(new CreateSimulatedPortfolioCommand.ItemCommand("AAPL", new BigDecimal("100"))));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "가상 포트폴리오")).willReturn(false);
        given(stockPort.loadStockByTicker("AAPL")).willReturn(Optional.of(apple));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("P006");
    }

    @Test
    @DisplayName("가상 포트폴리오 생성 실패: 배정 금액이 최신 EOD 종가보다 작아 수량이 0이면 거부한다")
    void createSimulatedPortfolio_rejectsZeroVirtualQuantity() {
        Stock samsung = stock("005930", Currency.KRW);
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                MEMBER_ID, "가상 포트폴리오", null, new BigDecimal("1"),
                List.of(new CreateSimulatedPortfolioCommand.ItemCommand("005930", new BigDecimal("100"))));

        given(portfolioPort.existsPortfolioName(MEMBER_ID, "가상 포트폴리오")).willReturn(false);
        given(stockPort.loadStockByTicker("005930")).willReturn(Optional.of(samsung));
        given(stockPricePort.findLatestByTicker("005930"))
                .willReturn(Optional.of(price(samsung, LocalDate.of(2026, 8, 7), new BigDecimal("10000000"))));

        assertThatThrownBy(() -> portfolioCommandService.createSimulatedPortfolio(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode.code")
                .isEqualTo("G001");
    }

    private Stock stock(String ticker, Currency currency) {
        return Stock.of(ticker, ticker, ticker, MarketType.KOSPI, currency, null, StockStatus.ACTIVE);
    }

    private StockPrice price(Stock stock, LocalDate asOfDate, BigDecimal closePrice) {
        return StockPrice.of(stock, asOfDate, closePrice, closePrice, closePrice, closePrice,
                closePrice, closePrice, 1L, BigDecimal.ONE, null);
    }

    @Test
    @DisplayName("포트폴리오 수정: 이름과 종목 구성을 정상적으로 업데이트한다")
    void updatePortfolio_Success() {
        // given
        Portfolio existingPortfolio = Portfolio.create(MEMBER_ID, "기존이름", "기존설명");
        UpdatePortfolioCommand command = new UpdatePortfolioCommand(MEMBER_ID, PORTFOLIO_ID, "새이름", "새설명", List.of());

        given(portfolioPort.loadPortfolio(PORTFOLIO_ID, MEMBER_ID)).willReturn(Optional.of(existingPortfolio));
        given(portfolioPort.existsPortfolioName(MEMBER_ID, "새이름")).willReturn(false);

        // when
        portfolioCommandService.updatePortfolio(command);

        // then
        assertThat(existingPortfolio.getName()).isEqualTo("새이름");
        assertThat(existingPortfolio.getDescription()).isEqualTo("새설명");
    }

    @Test
    @DisplayName("포트폴리오 삭제: 소유권을 확인하고 삭제를 호출한다")
    void deletePortfolio_Success() {
        // given
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "삭제할 포트", "설명");
        given(portfolioPort.loadPortfolio(PORTFOLIO_ID, MEMBER_ID)).willReturn(Optional.of(portfolio));

        // when
        portfolioCommandService.deletePortfolio(MEMBER_ID, PORTFOLIO_ID);

        // then
        verify(portfolioPort).deletePortfolio(PORTFOLIO_ID);
    }

    @Test
    @DisplayName("소유권 확인 실패: 포트폴리오는 존재하지만 소유자가 다른 경우 예외가 발생한다")
    void loadOwnedPortfolio_AccessDenied() {
        // given
        given(portfolioPort.loadPortfolio(PORTFOLIO_ID, MEMBER_ID)).willReturn(Optional.empty());
        given(portfolioPort.findById(PORTFOLIO_ID)).willReturn(Optional.of(mock(Portfolio.class)));

        // when & then
        assertThatThrownBy(() -> portfolioCommandService.deletePortfolio(MEMBER_ID, PORTFOLIO_ID))
                .isInstanceOf(PortfolioAccessDeniedException.class);
    }

    @Test
    @DisplayName("소유권 확인 실패: 포트폴리오 자체가 존재하지 않는 경우 예외가 발생한다")
    void loadOwnedPortfolio_NotFound() {
        // given
        given(portfolioPort.loadPortfolio(PORTFOLIO_ID, MEMBER_ID)).willReturn(Optional.empty());
        given(portfolioPort.findById(PORTFOLIO_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> portfolioCommandService.deletePortfolio(MEMBER_ID, PORTFOLIO_ID))
                .isInstanceOf(PortfolioNotFoundException.class);
    }
}
