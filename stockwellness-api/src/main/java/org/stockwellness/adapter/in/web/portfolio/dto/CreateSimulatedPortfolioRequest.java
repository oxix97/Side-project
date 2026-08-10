package org.stockwellness.adapter.in.web.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateSimulatedPortfolioRequest(
        @NotBlank String name,
        String description,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal totalAmount,
        @NotEmpty List<@Valid ItemRequest> items
) {
    public record ItemRequest(
            @NotBlank String symbol,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 3, fraction = 4) BigDecimal targetWeight
    ) {
    }
}
