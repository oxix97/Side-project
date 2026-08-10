package org.stockwellness.domain.portfolio.indicator;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 백테스트 API가 공개하는 벤치마크 코드와 내부 EOD 티커의 매핑입니다. */
@Getter
@RequiredArgsConstructor
public enum BenchmarkCode {
    KOSPI("KOSPI", "0001", "코스피"),
    KOSDAQ("KOSDAQ", "1001", "코스닥"),
    SP500("SP500", "SPX", "S&P 500");

    private final String code;
    private final String ticker;
    private final String displayName;

    public static BenchmarkCode fromCode(String value) {
        if (value == null || value.isBlank()) {
            return KOSPI;
        }
        return Arrays.stream(values())
                .filter(code -> code.code.equalsIgnoreCase(value)
                        || code.ticker.equalsIgnoreCase(value)
                        || (code == SP500 && ("S&P500".equalsIgnoreCase(value) || "S&P 500".equalsIgnoreCase(value))))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 벤치마크 코드입니다."));
    }
}
