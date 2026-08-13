package org.stockwellness.application.port.in.portfolio;

import org.stockwellness.application.port.in.portfolio.command.CreatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.CreateSimulatedPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.command.UpdatePortfolioCommand;
import org.stockwellness.application.port.in.portfolio.result.CreateSimulatedPortfolioResult;

public interface ManagePortfolioUseCase {
    Long createPortfolio(CreatePortfolioCommand command);
    CreateSimulatedPortfolioResult createSimulatedPortfolio(CreateSimulatedPortfolioCommand command);
    void updatePortfolio(UpdatePortfolioCommand command);
    void deletePortfolio(Long memberId, Long portfolioId);
}
