package org.stockwellness.application.port.in.portfolio.command;

import java.math.BigDecimal;
import java.util.List;

public record CreateSimulatedPortfolioCommand(
        Long memberId,
        String name,
        String description,
        BigDecimal totalAmount,
        List<ItemCommand> items
) {
    public record ItemCommand(
            String symbol,
            BigDecimal targetWeight
    ) {
    }
}
