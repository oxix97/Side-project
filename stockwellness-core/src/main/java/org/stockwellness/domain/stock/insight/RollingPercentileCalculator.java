package org.stockwellness.domain.stock.insight;

import java.math.BigDecimal;
import java.util.List;

public class RollingPercentileCalculator {

    /**
     * history 데이터 내에서 currentValue의 상대적 위치(백분위)를 계산합니다.
     * 0 ~ 100 사이의 점수를 반환합니다.
     * 데이터가 부족할 경우(5개 미만) 기본값 50을 반환합니다.
     *
     * @param currentValue 현재 값
     * @param history 과거 데이터 리스트 (비정렬 가능)
     * @return 백분위수 점수 (0-100)
     */
    public static int calculate(BigDecimal currentValue, List<BigDecimal> history) {
        if (currentValue == null || history == null) {
            return 50;
        }

        List<BigDecimal> sortedHistory = history.stream()
                .filter(val -> val != null)
                .sorted()
                .toList();

        if (sortedHistory.size() < 5) {
            return 50;
        }

        // currentValue보다 작은 값의 개수
        long countLess = sortedHistory.stream()
                .filter(val -> val.compareTo(currentValue) < 0)
                .count();
        
        // currentValue와 같은 값의 개수
        long countEqual = sortedHistory.stream()
                .filter(val -> val.compareTo(currentValue) == 0)
                .count();

        if (countEqual == 0) {
            return (int) ((double) countLess / sortedHistory.size() * 100);
        }

        // 동일 값들 중 중간 위치를 사용 (Mid-percentile)
        double rank = countLess + (countEqual / 2.0);
        
        // 극단값 보정
        if (currentValue.compareTo(sortedHistory.get(0)) <= 0) return 0;
        if (currentValue.compareTo(sortedHistory.get(sortedHistory.size() - 1)) >= 0) return 100;

        return (int) (rank / sortedHistory.size() * 100);
    }
}
