package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.stockwellness.application.port.in.portfolio.AiAdvisorUseCase;
import org.stockwellness.application.port.in.portfolio.DiagnosePortfolioUseCase;
import org.stockwellness.application.port.in.portfolio.LoadPortfolioUseCase;
import org.stockwellness.application.port.in.portfolio.ManagePortfolioUseCase;
import org.stockwellness.application.port.in.portfolio.PortfolioAnalysisUseCase;
import org.stockwellness.application.port.in.portfolio.command.BacktestPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.CreatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.CreateSimulatedPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.UpdatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioResponse;
import org.stockwellness.application.port.in.portfolio.result.*;
import org.stockwellness.application.service.portfolio.internal.BacktestResult;

/**
 * Portfolio Facade
 * 포트폴리오 관련 모든 서비스(생성, 조회, 분석, 진단, AI 조언)를 통합하는 단일 진입점입니다.
 * 컨트롤러와 내부 유즈케이스 간의 복잡한 의존성을 관리하고, 비즈니스 흐름을 조율하는 역할을 담당합니다.
 */
@Component
@RequiredArgsConstructor
public class PortfolioFacade {

    @Value("${app.ai.enabled:false}")
    private boolean aiEnabled;

    private final ManagePortfolioUseCase managePortfolioUseCase;
    private final LoadPortfolioUseCase loadPortfolioUseCase;
    private final PortfolioAnalysisUseCase portfolioAnalysisUseCase;
    private final DiagnosePortfolioUseCase diagnosePortfolioUseCase;
    private final AiAdvisorUseCase aiAdvisorUseCase;

    // -- 포트폴리오 관리 (Manage) --

    /**
     * 새로운 포트폴리오를 생성합니다.
     * @param command 포트폴리오 생성 정보 (이름, 설명, 초기 종목 등)
     * @return 생성된 포트폴리오의 ID
     */
    public Long createPortfolio(CreatePortfolioCommand command) {
        return managePortfolioUseCase.createPortfolio(command);
    }

    public CreateSimulatedPortfolioResult createSimulatedPortfolio(CreateSimulatedPortfolioCommand command) {
        return managePortfolioUseCase.createSimulatedPortfolio(command);
    }

    /**
     * 기존 포트폴리오의 정보를 수정합니다.
     * @param command 수정할 포트폴리오 정보 및 종목 리스트
     */
    public void updatePortfolio(UpdatePortfolioCommand command) {
        managePortfolioUseCase.updatePortfolio(command);
    }

    /**
     * 포트폴리오를 삭제합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 삭제할 포트폴리오 ID
     */
    public void deletePortfolio(Long memberId, Long portfolioId) {
        managePortfolioUseCase.deletePortfolio(memberId, portfolioId);
    }

    // -- 포트폴리오 조회 (Load) --

    /**
     * 사용자가 보유한 모든 포트폴리오 목록을 조회합니다.
     * @param memberId 사용자 ID
     * @return 포트폴리오 요약 정보 리스트
     */
    public List<PortfolioResponse> getMyPortfolios(Long memberId) {
        return loadPortfolioUseCase.getMyPortfolios(memberId);
    }

    /**
     * 특정 포트폴리오의 상세 정보를 조회합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 포트폴리오 상세 정보
     */
    public PortfolioResponse getPortfolio(Long memberId, Long portfolioId) {
        return loadPortfolioUseCase.getPortfolio(memberId, portfolioId);
    }

    // -- 포트폴리오 분석 (Analysis) --

    /**
     * 포트폴리오의 현재 평가 가치 및 수익률 정보를 조회합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 현재가 기준 평가 결과
     */
    public PortfolioValuationResult getValuation(Long memberId, Long portfolioId) {
        return portfolioAnalysisUseCase.getValuation(memberId, portfolioId);
    }

    /**
     * 포트폴리오의 자산 분산 상태(종목별 비중)를 조회합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 자산 비중 정보
     */
    public PortfolioDiversificationResult getDiversification(Long memberId, Long portfolioId) {
        return portfolioAnalysisUseCase.getDiversification(memberId, portfolioId);
    }

    /**
     * 목표 비중과 현재 비중을 비교하여 리밸런싱 가이드를 제공합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 종목별 리밸런싱 필요 수량 및 금액
     */
    public PortfolioRebalancingResult getRebalancingGuide(Long memberId, Long portfolioId) {
        return portfolioAnalysisUseCase.getRebalancingGuide(memberId, portfolioId);
    }

    /**
     * 특정 기간 동안의 포트폴리오 성과 요약(수익률, 벤치마크 비교 등)을 조회합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @param startDate 분석 시작일
     * @param endDate 분석 종료일
     * @return 기간 성과 요약 결과
     */
    public PortfolioAnalysisSummaryResult getAnalysisSummary(Long memberId, Long portfolioId, LocalDate startDate, LocalDate endDate) {
        LocalDate end = (endDate != null) ? endDate : LocalDate.now();
        LocalDate start = (startDate != null) ? startDate : end.minusMonths(12);
        return portfolioAnalysisUseCase.getAnalysisSummary(memberId, portfolioId, start, end);
    }

    /**
     * 설정된 투자 전략 및 금액으로 과거 수익률 시뮬레이션(백테스트)을 실행합니다.
     * @param command 백테스트 설정 정보 (투자 전략, 금액, 리밸런싱 주기 등)
     * @return 시뮬레이션 결과 데이터
     */
    public BacktestResult runBacktest(BacktestPortfolioCommand command) {
        return portfolioAnalysisUseCase.runBacktest(command);
    }

    /**
     * 포트폴리오 내 종목 간의 상관관계 행렬을 조회합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 종목 간 상관관계 수치 (Map 형태)
     */
    public Map<String, Map<String, BigDecimal>> getCorrelationMatrix(Long memberId, Long portfolioId) {
        return portfolioAnalysisUseCase.getCorrelationMatrix(memberId, portfolioId);
    }

    public PortfolioInceptionPerformanceResult getPerformanceSinceInception(Long memberId, Long portfolioId) {
        return portfolioAnalysisUseCase.getPerformanceSinceInception(memberId, portfolioId);
    }

    public PortfolioInceptionChartResult getInceptionChart(Long memberId, Long portfolioId) {
        return portfolioAnalysisUseCase.getInceptionChart(memberId, portfolioId);
    }

    // -- 포트폴리오 진단 (Diagnosis) --

    /**
     * 포트폴리오의 건강 상태를 진단하여 오각형 지표 점수를 산출합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return 5개 카테고리별 진단 점수 및 종목 기여도 분석 결과
     */
    public PortfolioHealthResult diagnosePortfolio(Long memberId, Long portfolioId) {
        if (!aiEnabled) {
            return PortfolioHealthResult.mock();
        }
        return diagnosePortfolioUseCase.diagnosePortfolio(memberId, portfolioId);
    }

    // -- AI 어드바이저 (Advisor) --

    /**
     * AI 어드바이저가 생성한 최신 포트폴리오 조언 및 리포트를 조회합니다.
     * @param memberId 사용자 ID
     * @param portfolioId 포트폴리오 ID
     * @return AI 조언 리포트 내용
     */
    public AdviceResponse getLatestAdvice(Long memberId, Long portfolioId) {
        if (!aiEnabled) {
            return AdviceResponse.mock();
        }
        return aiAdvisorUseCase.getLatestAdvice(memberId, portfolioId);
    }

    public AdviceResponse getNewAdvice(Long memberId, Long portfolioId) {
        if (!aiEnabled) {
            return AdviceResponse.mock();
        }
        return aiAdvisorUseCase.getNewAdvice(memberId, portfolioId);
    }
}
