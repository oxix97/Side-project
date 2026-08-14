package org.stockwellness.adapter.batch.sector.step.reader;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.stockwellness.adapter.out.external.kis.dto.SectorApiDto;
import org.stockwellness.application.port.in.batch.SectorEodSyncUseCase;
import org.stockwellness.application.port.out.stock.SectorDailyDetailPort;
import org.stockwellness.domain.stock.insight.SectorDailyDetail;
import org.stockwellness.domain.stock.insight.exception.SectorDomainException;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class SectorApiItemReader implements ItemReader<SectorApiDto> {

    private final SectorDailyDetailPort sectorDailyDetailPort;
    private final SectorEodSyncUseCase sectorEodSyncUseCase;
    @Value("#{jobParameters['targetDate']}")
    private String targetDateStr;

    private Iterator<SectorApiDto> sectorDataIterator;

    @Override
    public SectorApiDto read() {
        if (sectorDataIterator == null) {
            LocalDate targetDate = DateUtil.parseFlexible(targetDateStr);
            if (targetDate == null) {
                targetDate = DateUtil.today();
            }
            log.info("업종별 시세 상세 데이터 로드 시작. targetDate={}", targetDate);

            List<SectorDailyDetail> allDetails = sectorDailyDetailPort.findByBaseDate(targetDate);
            List<SectorApiDto> apiData = allDetails.stream()
                    .filter(detail -> isKisEligibleIndexCode(detail.getSectorCode()))
                    .map(this::toSectorApiDto)
                    .toList();

            if (apiData.isEmpty()) {
                throw new SectorDomainException(ErrorCode.SECTOR_DATA_NOT_FOUND);
            }

            if (isAllZeroSupply(apiData)) {
                log.warn("조회된 모든 섹터의 수급 데이터가 0입니다. eligibleRowCount={}, allZeroRowCount={}, sampleSectorCodes={}",
                        apiData.size(),
                        apiData.size(),
                        apiData.stream().limit(3).map(SectorApiDto::sectorCode).toList());
                throw new SectorDomainException(ErrorCode.SECTOR_DATA_NOT_FOUND);
            }

            sectorEodSyncUseCase.prepareSync(
                    targetDate,
                    apiData.stream().map(SectorApiDto::sectorCode).distinct().toList()
            );
            sectorDataIterator = apiData.iterator();
            log.info("업종별 시세 상세 데이터 로드 완료. total={}, eligible={}", allDetails.size(), apiData.size());
        }

        if (sectorDataIterator.hasNext()) {
            return sectorDataIterator.next();
        } else {
            return null;
        }
    }

    private boolean isAllZeroSupply(List<SectorApiDto> apiData) {
        return apiData.stream()
                .allMatch(d -> d.netForeignBuyAmount() == 0 && d.netInstBuyAmount() == 0);
    }

    private boolean isKisEligibleIndexCode(String indexCode) {
        if (Objects.isNull(indexCode)) {
            return false;
        }

        String normalized = indexCode.trim();
        if (normalized.isBlank()) {
            return false;
        }

        try {
            int parsed = Integer.parseInt(normalized);
            return parsed > 1 && parsed < 2000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private SectorApiDto toSectorApiDto(SectorDailyDetail detail) {
        return new SectorApiDto(
                detail.getSectorCode(),
                detail.getSectorName(),
                detail.getBaseDate(),
                detail.getCurrentPrice(),
                detail.getChangeRate(),
                detail.calculateAdvanceDeclineRatio(),
                detail.getNetForeignBuyAmount(),
                detail.getNetInstBuyAmount()
        );
    }
}
