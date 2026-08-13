package org.stockwellness.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.stockwellness.domain.portfolio.exception.InvalidPortfolioException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Portfolio 도메인 단위 테스트")
class PortfolioTest {

    @Nested
    @DisplayName("성공 케이스")
    class SuccessCases {

        @Test
        @DisplayName("포트폴리오 기본 정보(이름, 설명)를 수정할 수 있다")
        void update_basic_info_success() {
            // given
            Portfolio portfolio = Portfolio.create(1L, "기존 이름", "기존 설명");

            // when
            portfolio.updateBasicInfo("새 이름", "새 설명");

            // then
            assertThat(portfolio.getName()).isEqualTo("새 이름");
            assertThat(portfolio.getDescription()).isEqualTo("새 설명");
        }

        @Test
        @DisplayName("포트폴리오 아이템들의 목표 비중 합계가 100%여야 업데이트 가능하다")
        void update_items_with_target_weights_success() {
            // given
            Portfolio portfolio = Portfolio.create(1L, "비중 테스트", "");
            List<PortfolioItem> items = List.of(
                    PortfolioItem.createStock("AAPL", BigDecimal.ONE, BigDecimal.valueOf(150), "USD", BigDecimal.valueOf(60), LocalDate.now()),
                    PortfolioItem.createCash(BigDecimal.valueOf(400), "USD", BigDecimal.valueOf(40), LocalDate.now())
            );

            // when
            portfolio.updateItems(items);

            // then
            assertThat(portfolio.getItems()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("실패 케이스 (엣지 케이스 중심)")
    class FailureCases {

        @Test
        @DisplayName("목표 비중의 합계가 100%가 아니면 업데이트 시 예외가 발생한다")
        void fail_target_weight_sum_not_100() {
            // given
            Portfolio portfolio = Portfolio.create(1L, "비중 실패 테스트", "");
            List<PortfolioItem> items = List.of(
                    PortfolioItem.createStock("AAPL", BigDecimal.ONE, BigDecimal.valueOf(150), "USD", BigDecimal.valueOf(50), LocalDate.now()),
                    PortfolioItem.createCash(BigDecimal.valueOf(400), "USD", BigDecimal.valueOf(40), LocalDate.now()) // 합계 90
            );

            // when & then
            assertThatThrownBy(() -> portfolio.updateItems(items))
                    .isInstanceOf(InvalidPortfolioException.class);
        }

        @Test
        @DisplayName("엣지 케이스: 개별 아이템 수량이 딱 0이면 생성 시점에 예외가 발생한다")
        void fail_item_zero_quantity() {
            // when & then
            assertThatThrownBy(() -> PortfolioItem.createStock("AAPL", BigDecimal.ZERO, BigDecimal.valueOf(100), "KRW"))
                    .isInstanceOf(InvalidPortfolioException.class);
        }

        @Test
        @DisplayName("엣지 케이스: 개별 아이템 수량이 음수(-1)이면 생성 시점에 예외가 발생한다")
        void fail_item_negative_quantity() {
            // when & then
            assertThatThrownBy(() -> PortfolioItem.createCash(BigDecimal.valueOf(-1), "KRW"))
                    .isInstanceOf(InvalidPortfolioException.class);
        }

        @Test
        @DisplayName("엣지 케이스: 매입단가가 음수이면 생성 시점에 예외가 발생한다")
        void fail_item_negative_price() {
            // when & then
            assertThatThrownBy(() -> PortfolioItem.createStock("AAPL", BigDecimal.ONE, BigDecimal.valueOf(-100), "KRW"))
                    .isInstanceOf(InvalidPortfolioException.class);
        }

        @Test
        @DisplayName("엣지 케이스: 저장 정밀도를 넘는 목표 비중은 생성 시점에 예외가 발생한다")
        void fail_item_target_weight_more_than_four_decimal_places() {
            assertThatThrownBy(() -> PortfolioItem.createStock(
                    "AAPL", BigDecimal.ONE, BigDecimal.valueOf(100), "KRW", new BigDecimal("99.99999"), LocalDate.now()))
                    .isInstanceOf(InvalidPortfolioException.class);
        }
    }
}
