package org.stockwellness.application.service.portfolio.internal;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPricePort;

@Component
@RequiredArgsConstructor
public class SimulationDataProvider {

    private final StockPricePort stockPricePort;
    private final LoadBenchmarkPort loadBenchmarkPort;

    public SimulationData loadData(List<String> symbols, List<String> benchmarkTickers, LocalDate start, LocalDate end) {
        Map<String, List<StockPriceResult>> stockPrices = stockPricePort.loadPricesByTickers(symbols, start, end);

        // The first ticker is the requested primary benchmark. Preserve the
        // caller's order through SimulationData so comparisons and scalar
        // primary metrics remain aligned at the API boundary.
        Map<String, List<StockPriceResult>> benchmarkPrices = new LinkedHashMap<>();
        for (String ticker : benchmarkTickers == null ? Collections.<String>emptyList() : benchmarkTickers) {
            List<StockPriceResult> prices = loadBenchmarkPort.loadBenchmarkPrices(ticker, start, end);
            if (!prices.isEmpty()) {
                benchmarkPrices.put(ticker, prices);
            }
        }

        return new SimulationData(stockPrices, benchmarkPrices);
    }
}
