package org.stockwellness.adapter.in.web.portfolio.dto;

import java.time.LocalDate;

import org.stockwellness.application.port.in.portfolio.result.CreateSimulatedPortfolioResult;

public record CreateSimulatedPortfolioResponse(
        Long portfolioId,
        LocalDate asOfDate
) {
    public static CreateSimulatedPortfolioResponse from(CreateSimulatedPortfolioResult result) {
        return new CreateSimulatedPortfolioResponse(result.portfolioId(), result.asOfDate());
    }
}
