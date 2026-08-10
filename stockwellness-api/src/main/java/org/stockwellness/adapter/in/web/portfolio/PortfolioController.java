package org.stockwellness.adapter.in.web.portfolio;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.stockwellness.adapter.in.web.portfolio.dto.CreateSimulatedPortfolioRequest;
import org.stockwellness.adapter.in.web.portfolio.dto.CreateSimulatedPortfolioResponse;
import org.stockwellness.adapter.in.web.portfolio.dto.DiagnosisResponse;
import org.stockwellness.application.port.in.portfolio.command.CreatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.CreateSimulatedPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.UpdatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioCreateRequest;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioResponse;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioUpdateRequest;
import org.stockwellness.application.port.in.portfolio.result.AdviceResponse;
import org.stockwellness.application.port.in.portfolio.result.PortfolioHealthResult;
import org.stockwellness.application.service.portfolio.PortfolioFacade;
import org.stockwellness.global.common.response.ApiResponse;
import org.stockwellness.global.common.response.SuccessCode;
import org.stockwellness.global.logging.LogExecution;
import org.stockwellness.global.security.MemberPrincipal;

/**
 * 포트폴리오 상태성 API 컨트롤러.
 * 포트폴리오 본체(생성·목록·상세·수정·삭제)와 메인 화면/건강 진단 화면이 직접 소비하는
 * 건강 진단, AI 조언 엔드포인트를 제공합니다.
 */
@RestController
@LogExecution
@RequestMapping("/api/v1/portfolios")
@RequiredArgsConstructor
public class PortfolioController {
    
    private final PortfolioFacade portfolioFacade;

    /**
     * 새로운 포트폴리오를 생성합니다.
     * 포트폴리오 기본 정보와 초기 구성 종목(주식, 현금 등)을 입력받아 저장합니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @param request 포트폴리오 생성 요청 데이터
     * @return 생성된 포트폴리오의 ID
     */
    @PostMapping
    public ApiResponse<Long> createPortfolio(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @RequestBody @Valid PortfolioCreateRequest request) {

        CreatePortfolioCommand command = new CreatePortfolioCommand(
            memberPrincipal.id(),
            request.name(),
            request.description(),
            request.items().stream()
                .map(item -> new CreatePortfolioCommand.PortfolioItemCommand(
                    item.symbol(),
                    item.quantity(),
                    item.purchasePrice(),
                    item.currency(),
                    item.assetType(),
                    item.targetWeight()
                ))
                .toList()
        );

        Long portfolioId = portfolioFacade.createPortfolio(command);

        return ApiResponse.success(SuccessCode.CREATED, portfolioId);
    }

    @PostMapping("/simulated")
    public ApiResponse<CreateSimulatedPortfolioResponse> createSimulatedPortfolio(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @RequestBody @Valid CreateSimulatedPortfolioRequest request
    ) {
        CreateSimulatedPortfolioCommand command = new CreateSimulatedPortfolioCommand(
                memberPrincipal.id(),
                request.name(),
                request.description(),
                request.totalAmount(),
                request.items().stream()
                        .map(item -> new CreateSimulatedPortfolioCommand.ItemCommand(item.symbol(), item.targetWeight()))
                        .toList());

        return ApiResponse.success(
                SuccessCode.CREATED,
                CreateSimulatedPortfolioResponse.from(portfolioFacade.createSimulatedPortfolio(command)));
    }

    /**
     * 현재 로그인한 사용자의 모든 포트폴리오 목록을 요약 조회합니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @return 포트폴리오 목록 리스트
     */
    @GetMapping
    public ApiResponse<List<PortfolioResponse>> getMyPortfolios(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal) {

        List<PortfolioResponse> responses = portfolioFacade.getMyPortfolios(memberPrincipal.id());
        return ApiResponse.success(responses);
    }

    /**
     * 특정 포트폴리오의 상세 정보(구성 종목 및 메타 정보)를 조회합니다.
     * 포트폴리오 메인 화면과 편집 화면에서 보유 종목 목록을 렌더링할 때 사용합니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @param portfolioId 조회할 포트폴리오 ID
     * @return 포트폴리오 상세 데이터
     */
    @GetMapping("/{portfolioId}")
    public ApiResponse<PortfolioResponse> getPortfolio(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable Long portfolioId) {

        PortfolioResponse response = portfolioFacade.getPortfolio(memberPrincipal.id(), portfolioId);
        return ApiResponse.success(response);
    }

    /**
     * 포트폴리오의 기본 정보 및 구성 종목 리스트를 전체 수정합니다.
     * 수정 시 종목의 추가, 삭제, 수량 변경 및 목표 비중 재설정이 가능합니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @param portfolioId 수정할 포트폴리오 ID
     * @param request 수정 요청 데이터
     * @return 성공 여부
     */
    @PutMapping("/{portfolioId}")
    public ApiResponse<Void> updatePortfolio(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable Long portfolioId,
            @RequestBody @Valid PortfolioUpdateRequest request) {

        UpdatePortfolioCommand command = new UpdatePortfolioCommand(
            memberPrincipal.id(),
            portfolioId,
            request.name(),
            request.description(),
            request.items().stream()
                .map(item -> new UpdatePortfolioCommand.PortfolioItemCommand(
                    item.symbol(),
                    item.quantity(),
                    item.purchasePrice(),
                    item.currency(),
                    item.assetType(),
                    item.targetWeight()
                ))
                .toList()
        );

        portfolioFacade.updatePortfolio(command);
        return ApiResponse.success();
    }

    /**
     * 특정 포트폴리오를 삭제합니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @param portfolioId 삭제할 포트폴리오 ID
     * @return 성공 여부
     */
    @DeleteMapping("/{portfolioId}")
    public ApiResponse<Void> deletePortfolio(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable Long portfolioId) {

        portfolioFacade.deletePortfolio(memberPrincipal.id(), portfolioId);
        return ApiResponse.success();
    }

    /**
     * 포트폴리오 건강 진단 결과를 조회합니다.
     * 포트폴리오 메인 화면의 건강 배지와 건강 진단 화면의 레이더 차트/다음 액션에 함께 사용됩니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @param portfolioId 진단할 포트폴리오 ID
     * @return 진단 결과 데이터 (오각형 점수, AI 분석 요약 등)
     */
    @GetMapping("/{portfolioId}/health")
    public ApiResponse<DiagnosisResponse> diagnosePortfolio(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable Long portfolioId) {

        PortfolioHealthResult result = portfolioFacade.diagnosePortfolio(memberPrincipal.id(), portfolioId);
        return ApiResponse.success(DiagnosisResponse.from(result));
    }

    /**
     * 해당 포트폴리오의 최신 AI 조언을 조회합니다.
     * 메인 화면과 건강 진단 화면에서 동일한 최신 조언 카드를 렌더링할 때 사용합니다.
     *
     * @param memberPrincipal 인증된 사용자 정보
     * @param portfolioId 포트폴리오 ID
     * @return 최신 AI 조언 리포트
     */
    @GetMapping("/{portfolioId}/advice/latest")
    public ApiResponse<AdviceResponse> getLatestAdvice(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable Long portfolioId) {

        AdviceResponse response = portfolioFacade.getLatestAdvice(memberPrincipal.id(), portfolioId);
        return ApiResponse.success(response);
    }

    /**
     * 최신 조언이 없을 때 즉시 새 AI 조언을 생성합니다.
     * 프론트엔드는 최신 조언 조회가 404일 경우 이 엔드포인트로 fallback 합니다.
     */
    @PostMapping("/{portfolioId}/advice")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdviceResponse> createAdvice(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable Long portfolioId) {

        AdviceResponse response = portfolioFacade.getNewAdvice(memberPrincipal.id(), portfolioId);
        return ApiResponse.success(SuccessCode.CREATED, response);
    }
}
