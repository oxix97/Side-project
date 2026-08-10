package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.in.portfolio.result.AdviceResponse;
import org.stockwellness.application.port.out.portfolio.AdvisorAiContext;
import org.stockwellness.application.port.out.portfolio.AiAdviceProviderPort;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.service.portfolio.internal.AdvisorAiDataLoader;
import org.stockwellness.application.service.portfolio.internal.BacktestResult;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.advisor.AdviceAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiAdvisorService 단위 테스트")
class AiAdvisorServiceTest {

    @InjectMocks
    private AiAdvisorService aiAdvisorService;

    @Mock
    private PortfolioPort portfolioPort;

    @Mock
    private AdvisorAiDataLoader dataLoader;

    @Mock
    private AiAdviceProviderPort aiAdviceProviderPort;

    @Test
    @DisplayName("AI 리밸런싱 조언을 성공적으로 생성한다")
    void getNewAdvice_success() {
        // given
        Long memberId = 1L;
        Long portfolioId = 100L;
        Portfolio portfolio = Portfolio.create(memberId, "테스트 포트폴리오", "");
        
        given(portfolioPort.loadPortfolio(portfolioId, memberId)).willReturn(Optional.of(portfolio));
        
        AdvisorAiContext context = new AdvisorAiContext(portfolio.getName(), List.of(), List.of());
        given(dataLoader.loadContext(portfolioId)).willReturn(context);
        
        AiAdviceProviderPort.AdvisorAiResult aiResult = new AiAdviceProviderPort.AdvisorAiResult(
                "Target OK", "Technical Good", "Low Risk", "조언 내용", AdviceAction.REBALANCE
        );
        given(aiAdviceProviderPort.getRebalancingAdvice(context)).willReturn(aiResult);

        // when
        AdviceResponse response = aiAdvisorService.getNewAdvice(memberId, portfolioId);

        // then
        assertThat(response.content()).isEqualTo("조언 내용");
        assertThat(response.action()).isEqualTo(AdviceAction.REBALANCE);
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("AI 제공자 호출이 실패해도 fallback 조언을 반환한다")
    void getNewAdvice_returnsFallback_whenAiProviderFails() {
        Long memberId = 1L;
        Long portfolioId = 100L;
        Portfolio portfolio = Portfolio.create(memberId, "테스트 포트폴리오", "");

        given(portfolioPort.loadPortfolio(portfolioId, memberId)).willReturn(Optional.of(portfolio));

        AdvisorAiContext context = new AdvisorAiContext(portfolio.getName(), List.of(), List.of());
        given(dataLoader.loadContext(portfolioId)).willReturn(context);
        given(aiAdviceProviderPort.getRebalancingAdvice(context)).willThrow(new IllegalStateException("AI down"));

        AdviceResponse response = aiAdvisorService.getNewAdvice(memberId, portfolioId);

        assertThat(response.content()).contains("리밸런싱 조언을 생성하지 못했습니다");
        assertThat(response.action()).isEqualTo(AdviceAction.REBALANCE);
        verify(aiAdviceProviderPort).getRebalancingAdvice(context);
    }

    @Test
    @DisplayName("DCA 백테스트 조언은 null CAGR 대신 XIRR을 사용한다")
    void generateBacktestAdvice_dcaUsesXirrWhenCagrIsNull() {
        BacktestResult dcaResult = new BacktestResult(
                Collections.emptyList(),
                null,
                BigDecimal.valueOf(-8),
                BigDecimal.valueOf(-3),
                BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(-4),
                Collections.emptyMap(),
                Collections.emptyList(),
                null,
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(10),
                "DCA_XIRR_TWR",
                BigDecimal.valueOf(1.4),
                20L
        );

        String advice = aiAdvisorService.generateBacktestAdvice(dcaResult, "DCA", "SPX");

        assertThat(advice).contains("XIRR");
        assertThat(advice).contains("12.00%");
    }

    @Test
    @DisplayName("거치식 백테스트 조언은 CAGR을 백분율 포인트로 표시한다")
    void generateBacktestAdvice_lumpSumUsesCagrWithoutRescaling() {
        BacktestResult lumpSumResult = new BacktestResult(
                Collections.emptyList(),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(-8),
                BigDecimal.valueOf(-3),
                BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(25),
                BigDecimal.valueOf(-4),
                Collections.emptyMap(),
                Collections.emptyList(),
                null,
                null,
                BigDecimal.valueOf(20),
                "LUMP_SUM_CAGR_TWR",
                BigDecimal.valueOf(1.4),
                20L
        );

        String advice = aiAdvisorService.generateBacktestAdvice(lumpSumResult, "LUMP_SUM", "SPX");

        assertThat(advice).contains("CAGR");
        assertThat(advice).contains("15.00%");
        assertThat(advice).doesNotContain("1500.00%");
    }
}
