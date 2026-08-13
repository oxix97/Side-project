package org.stockwellness.application.service.portfolio.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("SimulationDataProvider 단위 테스트")
class SimulationDataProviderTest {

    @InjectMocks
    private SimulationDataProvider simulationDataProvider;

    @Mock
    private StockPricePort stockPricePort;

    @Mock
    private LoadBenchmarkPort loadBenchmarkPort;

    @Test
    @DisplayName("종목 리스트와 벤치마크 지수의 최근 2년 시세 데이터를 벌크 로딩한다")
    void load_simulation_data() {
        // given
        List<String> symbols = List.of("AAPL", "005930");
        List<String> benchmarkTickers = List.of("KOSPI");
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(2);

        StockPriceResult aaplPrice = new StockPriceResult(start, BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(95), BigDecimal.valueOf(102), BigDecimal.valueOf(102), 1000L, null, null, null, null, null);
        StockPriceResult benchmarkPrice = new StockPriceResult(start, BigDecimal.valueOf(2500), BigDecimal.valueOf(2550), BigDecimal.valueOf(2450), BigDecimal.valueOf(2520), BigDecimal.valueOf(2520), 1000000L, null, null, null, null, null);

        given(stockPricePort.loadPricesByTickers(anyList(), any(LocalDate.class), any(LocalDate.class)))
                .willReturn(Map.of("AAPL", List.of(aaplPrice)));
        given(loadBenchmarkPort.loadBenchmarkPrices(anyString(), any(LocalDate.class), any(LocalDate.class))).willReturn(List.of(benchmarkPrice));

        // when
        SimulationData data = simulationDataProvider.loadData(symbols, benchmarkTickers, start, end);

        // then
        assertThat(data.stockPrices().get("AAPL")).hasSize(1);
        assertThat(data.benchmarkPrices()).containsKey("KOSPI");
        assertThat(data.benchmarkPrices().get("KOSPI").get(0).baseDate()).isEqualTo(start);
    }

    @Test
    @DisplayName("벤치마크 티커가 없으면 주가 데이터만 로딩한다")
    void load_simulation_data_without_benchmarks() {
        List<String> symbols = List.of("AAPL");
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(2);

        StockPriceResult aaplPrice = new StockPriceResult(start, BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(95), BigDecimal.valueOf(102), BigDecimal.valueOf(102), 1000L, null, null, null, null, null);

        given(stockPricePort.loadPricesByTickers(anyList(), any(LocalDate.class), any(LocalDate.class)))
                .willReturn(Map.of("AAPL", List.of(aaplPrice)));

        SimulationData data = simulationDataProvider.loadData(symbols, null, start, end);

        assertThat(data.stockPrices().get("AAPL")).hasSize(1);
        assertThat(data.benchmarkPrices()).isEmpty();
    }

    @Test
    @DisplayName("벤치마크 결과는 요청한 비교 순서를 보존한다")
    void preserves_requested_benchmark_order() {
        List<String> symbols = List.of("005930");
        // API는 외부 코드 순서를 검증한 뒤 내부 저장소 코드(SPX, 0001, 1001)로
        // 변환해 provider에 전달합니다. 이 키들은 HashMap 버킷이 달라 순서 손실을 재현합니다.
        List<String> benchmarkTickers = List.of("SPX", "0001", "1001");
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(2);

        StockPriceResult price = new StockPriceResult(
                start,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(105),
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(102),
                BigDecimal.valueOf(102),
                1000L,
                null,
                null,
                null,
                null,
                null
        );
        given(stockPricePort.loadPricesByTickers(anyList(), any(LocalDate.class), any(LocalDate.class)))
                .willReturn(Map.of("005930", List.of(price)));
        given(loadBenchmarkPort.loadBenchmarkPrices(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .willReturn(List.of(price));

        SimulationData data = simulationDataProvider.loadData(symbols, benchmarkTickers, start, end);

        assertThat(new ArrayList<>(data.benchmarkPrices().keySet()))
                .containsExactlyElementsOf(benchmarkTickers);
    }
}
