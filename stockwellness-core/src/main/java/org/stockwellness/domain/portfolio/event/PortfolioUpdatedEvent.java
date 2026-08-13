package org.stockwellness.domain.portfolio.event;

/**
 * 포트폴리오 구성 종목이나 정보가 변경되었을 때 발행되는 도메인 이벤트입니다.
 */
public record PortfolioUpdatedEvent(
        Long portfolioId
) {
}
