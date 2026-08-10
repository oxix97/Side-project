package org.stockwellness.integration.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.stockwellness.adapter.in.web.portfolio.dto.BacktestRequest;
import org.stockwellness.adapter.in.web.portfolio.dto.CreateSimulatedPortfolioRequest;
import org.stockwellness.adapter.out.persistence.member.MemberRepository;
import org.stockwellness.adapter.out.persistence.portfolio.PortfolioRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.StockPriceRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.StockRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.BenchmarkPriceRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.BenchmarkRepository;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioCreateRequest;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioItemRequest;
import org.stockwellness.domain.portfolio.AssetType;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.RebalancingPeriod;
import org.stockwellness.domain.stock.Currency;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.StockStatus;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.domain.stock.price.BenchmarkPrice;
import org.stockwellness.fixture.StockFixture;
import org.stockwellness.integration.common.BaseIntegrationTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class PortfolioIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private StockPriceRepository stockPriceRepository;

    @Autowired
    private BenchmarkPriceRepository benchmarkPriceRepository;

    @Autowired
    private BenchmarkRepository benchmarkRepository;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        memberRepository.deleteAll();
        stockRepository.deleteAll();

        // 사용자 로그인 및 토큰 획득 (자동으로 Member 저장됨)
        accessToken = loginAndGetToken("test@example.com", "tester");

        // 테스트용 종목 생성
        Stock samsung = StockFixture.createSamsung();
        stockRepository.saveAndFlush(samsung);
        Stock skHynix = Stock.of("000660", "KR7000660001", "SK하이닉스", MarketType.KOSPI, Currency.KRW, null, StockStatus.ACTIVE);
        stockRepository.saveAndFlush(skHynix);

        // 테스트용 벤치마크 지수 생성 (KOSPI)
        Stock kospi = Stock.ofIndex("KOSPI", "코스피");
        stockRepository.saveAndFlush(kospi);

        // 시세 데이터 생성 (어제, 오늘)
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        stockPriceRepository.save(StockPrice.of(
                samsung, yesterday, new BigDecimal("50000"), new BigDecimal("51000"), new BigDecimal("49000"),
                new BigDecimal("50500"), new BigDecimal("50500"), new BigDecimal("50000"), 1000000L, 
                new BigDecimal("50000000000"), null));
        
        stockPriceRepository.save(StockPrice.of(
                samsung, today, new BigDecimal("50500"), new BigDecimal("52000"), new BigDecimal("50000"),
                new BigDecimal("51500"), new BigDecimal("51500"), new BigDecimal("50500"), 1200000L, 
                new BigDecimal("60000000000"), null));

        stockPriceRepository.save(StockPrice.of(
                skHynix, today, new BigDecimal("200000"), new BigDecimal("205000"), new BigDecimal("195000"),
                new BigDecimal("200000"), new BigDecimal("200000"), new BigDecimal("198000"), 900000L,
                new BigDecimal("180000000000"), null));

        stockPriceRepository.save(StockPrice.of(
                kospi, yesterday, new BigDecimal("2500"), new BigDecimal("2510"), new BigDecimal("2490"),
                new BigDecimal("2505"), new BigDecimal("2505"), new BigDecimal("2500"), 500000L, 
                new BigDecimal("100000000000"), null));

        stockPriceRepository.save(StockPrice.of(
                kospi, today, new BigDecimal("2505"), new BigDecimal("2530"), new BigDecimal("2500"),
                new BigDecimal("2520"), new BigDecimal("2520"), new BigDecimal("2505"), 600000L, 
                new BigDecimal("120000000000"), null));

        // 백테스트는 benchmark_price를 기준 데이터로 사용하므로, 테스트 지수도
        // 동일한 EOD 계약으로 저장한다. StockPrice의 지수 행만으로는
        // benchmark 전용 조회 포트가 빈 결과를 반환하여 S002가 된다.
        benchmarkPriceRepository.save(BenchmarkPrice.of("코스피", "0001", yesterday, new BigDecimal("2505")));
        benchmarkPriceRepository.save(BenchmarkPrice.of("코스피", "0001", today, new BigDecimal("2520")));
        
        stockPriceRepository.flush();
        benchmarkPriceRepository.flush();
        assertThat(benchmarkPriceRepository.findByTickerAndBaseDate("0001", today)).isPresent();
        assertThat(benchmarkRepository.findBenchmarkPrices("0001", LocalDate.of(1990, 1, 1), today))
                .hasSize(2);
    }

    @Test
    @DisplayName("포트폴리오 생성 통합 테스트: 생성 후 목록 조회 시 포함되어야 한다")
    void createAndGetPortfolio_Success() throws Exception {
        // given: 포트폴리오 생성 요청
        PortfolioItemRequest itemRequest = new PortfolioItemRequest("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", AssetType.STOCK, BigDecimal.valueOf(100));
        PortfolioCreateRequest createRequest = new PortfolioCreateRequest("내 통합 포트폴리오", "설명", List.of(itemRequest));

        // when: 생성 요청
        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("S001"));

        // then: 목록 조회 확인
        mockMvc.perform(get("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + accessToken))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S000"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("내 통합 포트폴리오"));
    }

    @Test
    @DisplayName("가상 포트폴리오 통합 테스트: 공통 EOD 종가로 계산한 양수 수량과 기준일을 저장한다")
    void createSimulatedPortfolio_Success() throws Exception {
        CreateSimulatedPortfolioRequest request = new CreateSimulatedPortfolioRequest(
                "가상 통합 포트폴리오",
                "설명",
                new BigDecimal("10000000"),
                List.of(
                        new CreateSimulatedPortfolioRequest.ItemRequest("005930", new BigDecimal("60")),
                        new CreateSimulatedPortfolioRequest.ItemRequest("000660", new BigDecimal("40"))));

        String response = mockMvc.perform(post("/api/v1/portfolios/simulated")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.data.asOfDate").value(LocalDate.now().toString()))
                .andReturn().getResponse().getContentAsString();

        Long portfolioId = objectMapper.readTree(response).path("data").path("portfolioId").asLong();
        Portfolio portfolio = portfolioRepository.findAllWithItemsByIdIn(List.of(portfolioId)).getFirst();
        assertThat(portfolio.getItems())
                .extracting(PortfolioItem::getSymbol, PortfolioItem::getQuantity, PortfolioItem::getPurchasePrice, PortfolioItem::getCurrency)
                .containsExactlyInAnyOrder(
                        tuple("005930", new BigDecimal("116.504854"), new BigDecimal("51500"), "KRW"),
                        tuple("000660", new BigDecimal("20.000000"), new BigDecimal("200000"), "KRW"));
        assertThat(portfolio.getItems()).allSatisfy(item -> assertThat(item.getQuantity()).isPositive());
    }

    @Test
    @DisplayName("백테스트 통합 테스트: 생성된 포트폴리오에 대해 백테스트 시뮬레이션을 실행한다")
    void runBacktest_Success() throws Exception {
        // [Given] 1. 포트폴리오 생성
        PortfolioItemRequest itemRequest = new PortfolioItemRequest("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", AssetType.STOCK, BigDecimal.valueOf(100));
        PortfolioCreateRequest createRequest = new PortfolioCreateRequest("백테스트 포트폴리오", "설명", List.of(itemRequest));

        String createResponse = mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long portfolioId = objectMapper.readTree(createResponse).get("data").asLong();

        // [Given] 2. 백테스트 요청 데이터
        BacktestRequest backtestRequest = new BacktestRequest(
                "LUMP_SUM", BigDecimal.valueOf(10000000), "KOSPI", "ALL", true, RebalancingPeriod.MONTHLY, null
        );

        // [When] 3. 백테스트 실행
        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", portfolioId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(backtestRequest)))
                .andDo(print())
                // [Then] 4. 결과 검증
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cagr").exists());
    }
}
