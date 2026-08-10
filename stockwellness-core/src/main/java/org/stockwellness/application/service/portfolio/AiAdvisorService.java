package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.application.port.in.portfolio.AiAdvisorUseCase;
import org.stockwellness.application.port.in.portfolio.result.AdviceResponse;
import org.stockwellness.application.port.out.portfolio.AdvisorAiContext;
import org.stockwellness.application.port.out.portfolio.AiAdviceProviderPort;
import org.stockwellness.application.port.out.portfolio.LoadAdvisorPort;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.service.portfolio.internal.AdvisorAiDataLoader;
import org.stockwellness.application.service.portfolio.internal.BacktestResult;
import org.stockwellness.domain.portfolio.exception.PortfolioAccessDeniedException;
import org.stockwellness.domain.portfolio.exception.PortfolioNotFoundException;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;

/**
 * AI 어드바이저 서비스
 * 포트폴리오 상태를 분석하여 AI 기반의 투자 조언을 생성하고 관리합니다.
 * 백테스트 결과를 분석하여 사용자에게 이해하기 쉬운 리포트를 제공하는 로직을 포함합니다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiAdvisorService implements AiAdvisorUseCase {

    private final PortfolioPort portfolioPort;
    private final AdvisorAiDataLoader dataLoader;
    private final AiAdviceProviderPort aiAdviceProviderPort;
    private final LoadAdvisorPort loadAdvisorPort;

    /**
     * 포트폴리오의 현재 상태를 바탕으로 새로운 AI 조언을 생성합니다.
     * 외부 AI 엔진(LLM 등)과 연동하여 리밸런싱 및 투자 전략을 제안받습니다.
     *
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 생성된 AI 조언 내용 및 권장 액션
     */
    @Override
    @Transactional
    public AdviceResponse getNewAdvice(Long memberId, Long portfolioId) {
        validateOwnership(memberId, portfolioId);

        // 진단에 필요한 데이터 로드 (종목 정보, 시세, 지표 등)
        var context = dataLoader.loadContext(portfolioId);
        // AI 제공자 포트를 통해 조언 생성 요청
        var aiResult = getAdviceSafely(context, portfolioId);

        return new AdviceResponse(
                aiResult.adviceContent(),
                aiResult.primaryAction(),
                LocalDateTime.now()
        );
    }

    /**
     * 특정 포트폴리오에 대해 가장 최근에 생성된 AI 조언 리포트를 조회합니다.
     *
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 저장된 최신 조언 리포트
     */
    @Override
    public AdviceResponse getLatestAdvice(Long memberId, Long portfolioId) {
        validateOwnership(memberId, portfolioId);

        return loadAdvisorPort.loadLatestReport(portfolioId)
                .map(report -> new AdviceResponse(
                        report.getContent(),
                        report.getAction(),
                        report.getCreatedAt()
                ))
                .orElseThrow(() -> new GlobalException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 백테스트 시뮬레이션 결과를 분석하여 자연어 형태의 종합 리포트를 생성합니다.
     * 수익성(CAGR), 안정성(MDD), 효율성(Sharpe), 시장 민감도(Beta) 등을 종합적으로 평가합니다.
     *
     * @param result 백테스트 엔진의 계산 결과
     * @param strategy 투자 전략 (거치식/적립식)
     * @param benchmark 비교 기준 지수 이름
     * @return 분석 리포트 문자열
     */
    @Override
    public String generateBacktestAdvice(BacktestResult result, String strategy, String benchmark) {
        // LUMP_SUM은 CAGR, DCA는 현금흐름을 반영한 XIRR을 우선 사용합니다.
        // XIRR이 계산 불가능한 짧은 기간에는 TWR을 표시하되, null을 0으로
        // 바꾸어 실제로 계산되지 않은 수익률을 보고하지 않습니다.
        boolean dcaStrategy = "DCA".equalsIgnoreCase(strategy);
        String returnMetric = dcaStrategy ? "XIRR" : "CAGR";
        BigDecimal annualizedReturn = dcaStrategy ? result.xirr() : result.cagr();
        if (annualizedReturn == null && result.timeWeightedReturnRate() != null) {
            annualizedReturn = result.timeWeightedReturnRate();
            returnMetric = "TWR";
        }
        // BacktestResult rates are already percentage points (e.g. 12 means
        // 12%), matching the API contract. Keep the display unit unchanged.
        BigDecimal annualizedPercent = annualizedReturn;
        BigDecimal mdd = result.mdd().abs();
        BigDecimal sharpe = result.sharpeRatio();
        BigDecimal alpha = result.alpha();
        BigDecimal beta = result.beta();
        BigDecimal volatility = result.volatility();

        String strategyName = "LUMP_SUM".equals(strategy) ? "거액 적립(Lump-sum)" : "정기 적립(DCA)";
        StringBuilder advice = new StringBuilder();

        // 1. [총평] 성과 요약 (CAGR/XIRR/TWR 및 MDD 기반)
        advice.append(String.format("📊 [%s 전략 시뮬레이션 결과]\n", strategyName));
        if (annualizedPercent == null) {
            advice.append(String.format("지난 기간 동안 연환산 수익률(%s)을 계산할 수 없으며, 최악의 시기에도 %.2f%%(MDD) 수준으로 자산을 방어했습니다. ",
                    returnMetric, mdd));
        } else {
            advice.append(String.format("지난 기간 동안 연평균 %.2f%%의 수익률(%s)을 기록했으며, 최악의 시기에도 %.2f%%(MDD) 수준으로 자산을 방어했습니다. ",
                    annualizedPercent, returnMetric, mdd));
        }

        // 2. 수익성 및 지수 대비 성과 분석 (Alpha)
        if (alpha.compareTo(BigDecimal.ZERO) > 0) {
            // 알파(추가수익)가 양수인 경우: 시장 지수를 능가하는 성과
            advice.append(String.format("특히 벤치마크(%s) 대비 연간 %.2f%%의 추가 수익(Alpha)을 창출하며 시장 지수를 압도하는 탁월한 종목 선택 능력을 보여주었습니다. ", benchmark, alpha));
        } else {
            // 알파가 음수인 경우: 시장 지수 대비 낮은 성과
            advice.append(String.format("시장 지수(%s)와 유사하거나 소폭 낮은 성과를 보였으나, 장기적인 안정성을 확보하는 데 주력한 모습입니다. ", benchmark));
        }

        // 3. 투자 효율성 평가 (Sharpe Ratio)
        if (sharpe.compareTo(BigDecimal.valueOf(1.5)) >= 0) {
            // 샤프 지수 1.5 이상: 매우 우수함
            advice.append("위험 한 단위당 얻는 수익이 매우 높은 '고효율 포트폴리오'로 평가됩니다. ");
        } else if (sharpe.compareTo(BigDecimal.ONE) >= 0) {
            // 샤프 지수 1.0 이상: 양호함
            advice.append("위험 대비 수익률이 양호하여 장기 투자 시 복리 효과를 극대화하기에 적합합니다. ");
        }

        // 4. 위험 및 안정성 분석 (MDD, Volatility)
        if (mdd.compareTo(BigDecimal.valueOf(25)) > 0) {
            // 하락폭이 25%를 초과하는 경우: 고위험 경고
            advice.append("\n⚠️ 주의: 최대 낙폭이 큰 편이므로, 하락장에서의 심리적 압박이 클 수 있습니다. 변동성을 줄이기 위해 채권이나 현금성 자산 혼합을 고려해보세요. ");
        } else if (mdd.compareTo(BigDecimal.valueOf(10)) < 0) {
            // 하락폭이 10% 미만인 경우: 매우 안정적
            advice.append("\n✅ 안정성: 하락장에서도 자산 가치 하락을 효과적으로 방어하며 매우 탄탄한 방어력을 입증했습니다. ");
        }

        // 5. 시장 민감도 및 변동성 분석 (Beta, Volatility)
        if (beta.compareTo(BigDecimal.valueOf(1.2)) > 0) {
            // 베타 1.2 초과: 공격적 투자 성향
            advice.append(String.format("시장(%s)의 움직임보다 더 민감하게 반응하는 '공격적 성장형' 성향이 뚜렷합니다. 강세장에서 큰 수익이 기대되지만 약세장에서는 주의가 필요합니다. ", benchmark));
        } else if (beta.compareTo(BigDecimal.valueOf(0.8)) < 0) {
            // 베타 0.8 미만: 방어적 투자 성향
            advice.append(String.format("시장(%s) 변동에 둔감하게 반응하는 '저변동 방어형' 성향을 보입니다. 하락장에서 상대적인 수익률 방어 효과가 뛰어날 것입니다. ", benchmark));
        } else {
            // 베타 1.0 근방: 시장 추종형
            advice.append(String.format("시장 지수(%s)와 유사한 흐름을 보이는 '시장 추종형' 성향을 가지고 있습니다. ", benchmark));
        }

        if (volatility.compareTo(BigDecimal.valueOf(30)) > 0) {
            // 변동성 30% 초과: 높은 가격 진동
            advice.append("자산의 가격 변동폭이 매우 크므로, 목표 수익 도달 시 부분 수익 실현을 권장합니다.");
        }

        return advice.toString().trim();
    }

    /**
     * 사용자가 해당 포트폴리오에 대한 접근 권한이 있는지 확인합니다.
     */
    private void validateOwnership(Long memberId, Long portfolioId) {
        portfolioPort.loadPortfolio(portfolioId, memberId)
                .orElseThrow(() -> {
                    if (portfolioPort.findById(portfolioId).isPresent()) {
                        throw new PortfolioAccessDeniedException();
                    }
                    return new PortfolioNotFoundException();
                });
    }

    private AiAdviceProviderPort.AdvisorAiResult getAdviceSafely(AdvisorAiContext context, Long portfolioId) {
        try {
            return aiAdviceProviderPort.getRebalancingAdvice(context);
        } catch (Exception e) {
            log.error("AI 리밸런싱 조언 생성 실패. fallback 응답으로 대체합니다. portfolioId={}", portfolioId, e);
            return AiAdviceProviderPort.AdvisorAiResult.fallback();
        }
    }
}
