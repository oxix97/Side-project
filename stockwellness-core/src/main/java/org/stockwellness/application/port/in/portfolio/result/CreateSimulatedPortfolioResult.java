package org.stockwellness.application.port.in.portfolio.result;

import java.time.LocalDate;

public record CreateSimulatedPortfolioResult(
        Long portfolioId,
        LocalDate asOfDate
) {
}
