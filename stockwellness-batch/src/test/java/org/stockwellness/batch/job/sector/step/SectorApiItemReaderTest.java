package org.stockwellness.batch.job.sector.step;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.stockwellness.adapter.batch.sector.step.reader.SectorApiItemReader;
import org.stockwellness.application.port.in.batch.SectorEodSyncUseCase;
import org.stockwellness.application.port.out.stock.SectorDailyDetailPort;
import org.stockwellness.application.port.out.stock.SectorDailyDetailSnapshot;
import org.stockwellness.domain.stock.insight.SectorDailyDetail;
import org.stockwellness.domain.stock.insight.exception.SectorDomainException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SectorApiItemReaderTest {

    @InjectMocks
    private SectorApiItemReader reader;

    @Mock
    private SectorDailyDetailPort sectorDailyDetailPort;

    @Mock
    private SectorEodSyncUseCase sectorEodSyncUseCase;

    @Test
    @DisplayName("모든 섹터의 수급 데이터가 0이면 SectorDomainException을 던진다")
    void throwsException_whenAllSupplyIsZero() {
        LocalDate today = LocalDate.of(2026, 4, 9);
        ReflectionTestUtils.setField(reader, "targetDateStr", today.toString());

        List<SectorDailyDetail> allZeroData = List.of(
                newDetail("0001", "종합", today, 0L, 0L),
                newDetail("0029", "전기전자", today, 0L, 0L)
        );
        given(sectorDailyDetailPort.findByBaseDate(today)).willReturn(allZeroData);

        assertThatThrownBy(() -> reader.read())
                .isInstanceOf(SectorDomainException.class);
    }

    @Test
    @DisplayName("비대상 indexCode는 제외하고 eligible 섹터만 읽는다")
    void filtersNonEligibleCodesBeforePreparingSync() {
        LocalDate today = LocalDate.of(2026, 4, 9);
        ReflectionTestUtils.setField(reader, "targetDateStr", today.toString());

        given(sectorDailyDetailPort.findByBaseDate(today)).willReturn(List.of(
                newDetail("0001", "종합", today, 0L, 0L),
                newDetail("0029", "전기전자", today, 100L, 50L),
                newDetail("2001", "KOSPI200", today, 0L, 0L)
        ));

        var result = reader.read();

        assertThat(result).isNotNull();
        assertThat(result.sectorCode()).isEqualTo("0029");
        verify(sectorEodSyncUseCase).prepareSync(today, List.of("0029"));
    }

    @Test
    @DisplayName("저장된 당일 섹터 상세를 SectorApiDto로 변환해 읽는다")
    void readsData_fromCollectedDailyDetails() {
        LocalDate today = LocalDate.of(2026, 4, 9);
        ReflectionTestUtils.setField(reader, "targetDateStr", today.toString());

        given(sectorDailyDetailPort.findByBaseDate(today)).willReturn(List.of(
                newDetail("0029", "전기전자", today, 100L, 50L)
        ));

        var result = reader.read();

        assertThat(result).isNotNull();
        assertThat(result.sectorCode()).isEqualTo("0029");
        assertThat(result.sectorName()).isEqualTo("전기전자");
        assertThat(result.sectorIndexCurrentPrice()).isEqualByComparingTo("1000.12");
        assertThat(result.avgFluctuationRate()).isEqualByComparingTo("1.23");
        assertThat(result.advanceRatio()).isEqualByComparingTo("366.67");
        assertThat(result.netForeignBuyAmount()).isEqualTo(100L);
        assertThat(result.netInstBuyAmount()).isEqualTo(50L);
        verify(sectorEodSyncUseCase).prepareSync(today, List.of("0029"));
    }

    @Test
    @DisplayName("compact targetDate도 동일하게 파싱한다")
    void readsData_withCompactTargetDate() {
        LocalDate today = LocalDate.of(2026, 4, 9);
        ReflectionTestUtils.setField(reader, "targetDateStr", "20260409");

        given(sectorDailyDetailPort.findByBaseDate(today)).willReturn(List.of(
                newDetail("0029", "전기전자", today, 100L, 50L)
        ));

        var result = reader.read();

        assertThat(result).isNotNull();
        assertThat(result.sectorCode()).isEqualTo("0029");
        verify(sectorEodSyncUseCase).prepareSync(today, List.of("0029"));
    }

    private SectorDailyDetail newDetail(String sectorCode, String sectorName, LocalDate baseDate, long foreign, long inst) {
        return SectorDailyDetail.of(
                sectorCode,
                sectorName,
                new SectorDailyDetailSnapshot(
                        sectorCode,
                        baseDate,
                        new BigDecimal("1000.12"),
                        new BigDecimal("12.34"),
                        "2",
                        new BigDecimal("1.23"),
                        100L,
                        90L,
                        1000L,
                        900L,
                        new BigDecimal("990.00"),
                        new BigDecimal("1010.00"),
                        new BigDecimal("980.00"),
                        10,
                        1,
                        2,
                        3,
                        0,
                        new BigDecimal("1100.00"),
                        new BigDecimal("-9.08"),
                        baseDate,
                        new BigDecimal("800.00"),
                        new BigDecimal("25.01"),
                        baseDate.minusMonths(1),
                        100L,
                        200L,
                        new BigDecimal("33.33"),
                        new BigDecimal("66.67"),
                        100L,
                        foreign,
                        inst
                )
        );
    }
}
