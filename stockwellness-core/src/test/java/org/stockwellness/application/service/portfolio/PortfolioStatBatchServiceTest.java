package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.stockwellness.adapter.out.persistence.member.MemberRepository;
import org.stockwellness.adapter.out.persistence.portfolio.PortfolioRepository;
import org.stockwellness.adapter.out.persistence.portfolio.PortfolioStatsRepository;
import org.stockwellness.application.port.out.outbox.OutboxPort;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.service.portfolio.internal.BacktestEngine;
import org.stockwellness.application.service.portfolio.internal.BacktestResult;
import org.stockwellness.application.service.portfolio.internal.SimulationData;
import org.stockwellness.application.service.portfolio.internal.SimulationDataProvider;
import org.stockwellness.config.JpaConfig;
import org.stockwellness.config.QueryDslConfig;
import org.stockwellness.domain.member.LoginType;
import org.stockwellness.domain.member.Member;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.PortfolioStats;
import org.stockwellness.global.util.JsonUtil;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({PortfolioStatBatchService.class, JsonUtil.class, QueryDslConfig.class, JpaConfig.class})
class PortfolioStatBatchServiceTest {

    @Autowired
    private PortfolioStatBatchService portfolioStatBatchService;

    @Autowired
    private PortfolioStatsRepository portfolioStatsRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private MemberRepository memberRepository;

    @MockitoBean
    private SimulationDataProvider simulationDataProvider;

    @MockitoBean
    private BacktestEngine backtestEngine;

    @MockitoBean
    private OutboxPort outboxPort;

    @MockitoBean
    private PortfolioAnalysisService portfolioAnalysisService;

    @MockitoBean
    private PortfolioPort portfolioPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    @Test
    @DisplayName("테스트 컨텍스트 로딩 확인")
    void contextLoads() {
        assertThat(portfolioStatBatchService).isNotNull();
    }

    @Nested
    @DisplayName("포트폴리오 통계 배치 업데이트 테스트")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class UpdateStats {

        @Test
        @DisplayName("신규 저장: 통계가 없는 포트폴리오는 새로 생성된다")
        void createNew() {
            // given
            Long portfolioId = createTestPortfolio("test@test.com", "테스터1", "005930");
            Portfolio portfolio = loadPortfolioWithItems(portfolioId);

            given(portfolioPort.loadAllWithItems(anyList())).willReturn(List.of(portfolio));
            setupMocks();

            // when
            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(portfolioId));

            // then
            assertStats(portfolioId, "15.5", "1.2", "0.9");
            verify(outboxPort, times(1)).save(any());
        }

        @Test
        @DisplayName("기존 업데이트: 이미 통계가 있는 포트폴리오는 값이 업데이트된다")
        void updateExisting() {
            // given
            Long portfolioId = createTestPortfolio("update@test.com", "테스터2", "005930");
            Portfolio portfolio = loadPortfolioWithItems(portfolioId);
            
            transactionTemplate.executeWithoutResult(status -> {
                portfolioStatsRepository.save(PortfolioStats.create(portfolio, LocalDate.now().minusDays(1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            });

            given(portfolioPort.loadAllWithItems(anyList())).willReturn(List.of(portfolio));
            setupMocks();

            // when
            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(portfolioId));

            // then
            assertStats(portfolioId, "15.5", "1.2", "0.9");
            verify(simulationDataProvider).loadData(anyList(), argThat(tickers -> tickers != null && tickers.contains("0001")), any(), any());
        }

        @Test
        @DisplayName("부분 실패: 일부 포트폴리오 작업 중 예외가 발생해도 다른 포트폴리오는 처리된다")
        void partialFailure() {
            // given
            Long id1 = createTestPortfolio("user1@test.com", "회원1", "FAIL01");
            Long id2 = createTestPortfolio("user2@test.com", "회원2", "SUCC01");
            Portfolio p1 = loadPortfolioWithItems(id1);
            Portfolio p2 = loadPortfolioWithItems(id2);

            given(portfolioPort.loadAllWithItems(anyList())).willReturn(List.of(p1, p2));
            
            // "FAIL01"이 포함된 포트폴리오는 실패, "SUCC01"이 포함된 포트폴리오는 성공하도록 모킹
            given(backtestEngine.runLumpSum(any(), argThat(m -> m instanceof Map && ((Map<?, ?>) m).containsKey("FAIL01")), any(), any(), any(), any(), anyBoolean()))
                    .willThrow(new RuntimeException("엔진 오류"));
            given(backtestEngine.runLumpSum(any(), argThat(m -> m instanceof Map && ((Map<?, ?>) m).containsKey("SUCC01")), any(), any(), any(), any(), anyBoolean()))
                    .willReturn(new BacktestResult(List.of(), BigDecimal.ZERO, BigDecimal.valueOf(10.0), BigDecimal.ZERO, BigDecimal.valueOf(1.0), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1.0), BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), List.of(), null));

            given(simulationDataProvider.loadData(anyList(), any(), any(), any()))
                    .willReturn(new SimulationData(Map.of("FAIL01", List.of(), "SUCC01", List.of()), Map.of()));
            given(portfolioAnalysisService.calculateBenchmarkReturn(anyString(), any(), any())).willReturn(BigDecimal.valueOf(5.0));

            // when
            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(id1, id2));

            // then
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(portfolioStatsRepository.findByPortfolioId(id1)).isEmpty();
                assertThat(portfolioStatsRepository.findByPortfolioId(id2)).isPresent();
            });
        }

        @Test
        @DisplayName("필수 벤치마크 누락: 기존 통계를 0 또는 빈 값으로 덮어쓰지 않는다")
        void missingBenchmarkPreservesExistingStats() {
            Long portfolioId = createTestPortfolio("missing-benchmark@test.com", "기존 통계", "005930");
            Portfolio portfolio = loadPortfolioWithItems(portfolioId);
            transactionTemplate.executeWithoutResult(status -> portfolioStatsRepository.save(
                    PortfolioStats.create(
                            portfolio,
                            LocalDate.now().minusDays(1),
                            BigDecimal.valueOf(9.9),
                            BigDecimal.valueOf(2.2),
                            BigDecimal.valueOf(1.1),
                            BigDecimal.valueOf(8.8),
                            BigDecimal.valueOf(7.7)
                    )
            ));

            given(portfolioPort.loadAllWithItems(anyList())).willReturn(List.of(portfolio));
            given(simulationDataProvider.loadData(anyList(), anyList(), any(), any()))
                    .willReturn(new SimulationData(Map.of("005930", List.of()), Map.of()));
            given(backtestEngine.runLumpSum(any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .willThrow(new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND));

            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(portfolioId));

            transactionTemplate.executeWithoutResult(status -> {
                PortfolioStats stats = portfolioStatsRepository.findByPortfolioId(portfolioId).orElseThrow();
                assertThat(stats.getMdd()).isEqualByComparingTo("9.9");
                assertThat(stats.getSharpeRatio()).isEqualByComparingTo("2.2");
                assertThat(stats.getBeta()).isEqualByComparingTo("1.1");
            });
            verify(outboxPort, never()).save(any());
        }

        @Test
        @DisplayName("현금 및 0% 자산은 배치 백테스트에서 제외하고 주식 비중을 100%로 정규화한다")
        void filtersCashAndZeroWeightAssets() {
            Long portfolioId = createPortfolioWithCashAndZeroWeightStock();
            Portfolio portfolio = loadPortfolioWithItems(portfolioId);

            given(portfolioPort.loadAllWithItems(anyList())).willReturn(List.of(portfolio));
            setupMocks();

            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(portfolioId));

            verify(simulationDataProvider).loadData(
                    argThat(symbols -> symbols.size() == 1 && symbols.contains("005930")),
                    argThat(tickers -> tickers != null && tickers.contains("0001")),
                    any(),
                    any()
            );
            verify(backtestEngine).runLumpSum(
                    any(),
                    argThat(weights -> weights.size() == 1
                            && weights.containsKey("005930")
                            && weights.get("005930").compareTo(BigDecimal.valueOf(100)) == 0),
                    any(),
                    any(),
                    any(),
                    any(),
                    anyBoolean()
            );
        }

        @Test
        @DisplayName("자산 부재 건너뜀: 주식 자산이 없는 포트폴리오는 처리를 건너뛴다")
        void skipNoAssets() {
            // given
            Long portfolioId = transactionTemplate.execute(status -> {
                Member m = Member.register("empty@test.com", "무일푼", LoginType.GOOGLE);
                memberRepository.save(m);
                Portfolio p = Portfolio.create(m.getId(), "빈 포트폴리오", "");
                portfolioRepository.save(p);
                return p.getId();
            });
            Portfolio portfolio = loadPortfolioWithItems(portfolioId);

            given(portfolioPort.loadAllWithItems(anyList())).willReturn(List.of(portfolio));

            // when
            portfolioStatBatchService.updatePortfolioStatsBatch(List.of(portfolioId));

            // then
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(portfolioStatsRepository.findByPortfolioId(portfolioId)).isEmpty();
            });
        }

        private Long createTestPortfolio(String email, String nickname, String ticker) {
            return transactionTemplate.execute(status -> {
                Member member = Member.register(email, nickname, LoginType.GOOGLE);
                memberRepository.save(member);

                Portfolio portfolio = Portfolio.create(member.getId(), "포트폴리오", "설명");
                PortfolioItem item = PortfolioItem.createStock(ticker, BigDecimal.TEN, BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(100), LocalDate.now());
                portfolio.updateItems(List.of(item));
                
                portfolioRepository.save(portfolio);
                return portfolio.getId();
            });
        }

        private Long createPortfolioWithCashAndZeroWeightStock() {
            return transactionTemplate.execute(status -> {
                Member member = Member.register("cash-mix@test.com", "현금 혼합", LoginType.GOOGLE);
                memberRepository.save(member);

                Portfolio portfolio = Portfolio.create(member.getId(), "현금 혼합 포트폴리오", "");
                PortfolioItem stock = PortfolioItem.createStock(
                        "005930", BigDecimal.TEN, BigDecimal.valueOf(50000), "KRW",
                        BigDecimal.valueOf(70), LocalDate.now());
                PortfolioItem zeroWeightStock = PortfolioItem.createStock(
                        "000660", BigDecimal.TEN, BigDecimal.valueOf(50000), "KRW",
                        BigDecimal.ZERO, LocalDate.now());
                PortfolioItem cash = PortfolioItem.createCash(
                        BigDecimal.valueOf(300000), "KRW", BigDecimal.valueOf(30), LocalDate.now());
                portfolio.updateItems(new ArrayList<>(List.of(stock, zeroWeightStock, cash)));

                portfolioRepository.save(portfolio);
                return portfolio.getId();
            });
        }

        private Portfolio loadPortfolioWithItems(Long id) {
            return transactionTemplate.execute(status -> {
                Portfolio p = portfolioRepository.findById(id).orElseThrow();
                p.getItems().size();
                return p;
            });
        }

        private void setupMocks() {
            given(simulationDataProvider.loadData(anyList(), any(), any(), any())).willReturn(new SimulationData(Map.of("005930", List.of()), Map.of()));
            given(backtestEngine.runLumpSum(any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .willReturn(new BacktestResult(List.of(), BigDecimal.ZERO, BigDecimal.valueOf(15.5), BigDecimal.ZERO, BigDecimal.valueOf(1.2), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(0.9), BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), List.of(), null));
            given(portfolioAnalysisService.calculateBenchmarkReturn(anyString(), any(), any())).willReturn(BigDecimal.valueOf(5.0));
        }

        private void assertStats(Long id, String mdd, String sharpe, String beta) {
            transactionTemplate.executeWithoutResult(status -> {
                var stats = portfolioStatsRepository.findByPortfolioId(id).orElseThrow();
                assertThat(stats.getMdd()).isEqualByComparingTo(mdd);
                assertThat(stats.getSharpeRatio()).isEqualByComparingTo(sharpe);
                assertThat(stats.getBeta()).isEqualByComparingTo(beta);
            });
        }
    }
}
