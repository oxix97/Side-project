package org.stockwellness.adapter.out.persistence.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.stockwellness.adapter.out.persistence.member.MemberRepository;
import org.stockwellness.config.QueryDslConfig;
import org.stockwellness.domain.member.LoginType;
import org.stockwellness.domain.member.Member;
import org.stockwellness.domain.member.MemberRole;
import org.stockwellness.domain.member.MemberStatus;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.flyway.enabled=false")
@Import(QueryDslConfig.class)
@ActiveProfiles("test")
class PortfolioRepositoryTest {

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    private Long memberId;

    @BeforeEach
    void setUp() {
        Member member = Member.register("test@test.com", "tester", LoginType.GOOGLE);
        ReflectionTestUtils.setField(member, "role", MemberRole.USER);
        ReflectionTestUtils.setField(member, "status", MemberStatus.ACTIVE);
        ReflectionTestUtils.setField(member, "notificationRebalancing", true);
        ReflectionTestUtils.setField(member, "notificationMarketAlert", false);
        ReflectionTestUtils.setField(member, "notificationNewListing", false);
        ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.now());
        
        memberId = memberRepository.save(member).getId();
        memberRepository.flush();
    }

    @Test
    @DisplayName("포트폴리오 저장 및 조회: 연관된 아이템들과 함께 정상적으로 조회된다")
    void saveAndFindWithItems_Success() {
        // given
        Portfolio portfolio = Portfolio.create(memberId, "테스트 포트폴리오", "설명");
        ReflectionTestUtils.setField(portfolio, "createdAt", LocalDateTime.now());
        
        PortfolioItem item1 = PortfolioItem.createStock("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(50), LocalDate.now());
        ReflectionTestUtils.setField(item1, "createdAt", LocalDateTime.now());
        
        PortfolioItem item2 = PortfolioItem.createCash(BigDecimal.valueOf(500000), "KRW", BigDecimal.valueOf(50), LocalDate.now());
        ReflectionTestUtils.setField(item2, "createdAt", LocalDateTime.now());
        
        portfolio.updateItems(List.of(item1, item2));

        Portfolio saved = portfolioRepository.save(portfolio);
        portfolioRepository.flush();

        // when
        Optional<Portfolio> found = portfolioRepository.findWithItems(saved.getId(), memberId);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스트 포트폴리오");
        assertThat(found.get().getItems()).hasSize(2);
        assertThat(found.get().getItems()).extracting(PortfolioItem::getSymbol)
                .containsExactlyInAnyOrder("005930", "CASH");
    }

    @Test
    @DisplayName("가상 포트폴리오 수량 저장: flush와 clear 이후에도 소수점 여섯 자리를 보존한다")
    void saveAndFindSimulatedPortfolio_preservesSixDecimalQuantity() {
        Portfolio portfolio = Portfolio.create(memberId, "가상 포트폴리오", "설명");
        ReflectionTestUtils.setField(portfolio, "createdAt", LocalDateTime.now());
        PortfolioItem item = PortfolioItem.createSimulatedStock(
                "005930",
                new BigDecimal("116.504854"),
                new BigDecimal("51500"),
                "KRW",
                new BigDecimal("100"),
                LocalDate.of(2026, 8, 7));
        ReflectionTestUtils.setField(item, "createdAt", LocalDateTime.now());
        portfolio.updateItems(List.of(item));

        Portfolio saved = portfolioRepository.save(portfolio);
        entityManager.flush();
        entityManager.clear();

        Portfolio reloaded = portfolioRepository.findWithItems(saved.getId(), memberId).orElseThrow();
        assertThat(reloaded.getItems()).singleElement()
                .extracting(PortfolioItem::getQuantity)
                .isEqualTo(new BigDecimal("116.504854"));
    }

    @Test
    @DisplayName("회원별 포트폴리오 목록 조회: 특정 회원의 모든 포트폴리오를 아이템과 함께 조회한다")
    void findAllByMemberIdWithItems_Success() {
        // given
        Portfolio p1 = Portfolio.create(memberId, "포트1", "설명1");
        ReflectionTestUtils.setField(p1, "createdAt", LocalDateTime.now());
        p1.updateItems(List.of(PortfolioItem.createCash(BigDecimal.valueOf(100), "KRW", BigDecimal.valueOf(100), LocalDate.now())));
        p1.getItems().forEach(item -> ReflectionTestUtils.setField(item, "createdAt", LocalDateTime.now()));
        
        Portfolio p2 = Portfolio.create(memberId, "포트2", "설명2");
        ReflectionTestUtils.setField(p2, "createdAt", LocalDateTime.now());
        p2.updateItems(List.of(PortfolioItem.createStock("AAPL", BigDecimal.ONE, BigDecimal.valueOf(150), "USD", BigDecimal.valueOf(100), LocalDate.now())));
        p2.getItems().forEach(item -> ReflectionTestUtils.setField(item, "createdAt", LocalDateTime.now()));
        
        portfolioRepository.saveAll(List.of(p1, p2));
        portfolioRepository.flush();

        // when
        List<Portfolio> portfolios = portfolioRepository.findAllByMemberIdWithItems(memberId);

        // then
        assertThat(portfolios).hasSize(2);
        assertThat(portfolios).extracting(Portfolio::getName).containsExactlyInAnyOrder("포트1", "포트2");
    }

    @Test
    @DisplayName("Cascade Delete: 포트폴리오 삭제 시 연관된 아이템들도 함께 삭제된다")
    void deletePortfolio_CascadeItems() {
        // given
        Portfolio portfolio = Portfolio.create(memberId, "삭제 테스트", "설명");
        ReflectionTestUtils.setField(portfolio, "createdAt", LocalDateTime.now());
        portfolio.updateItems(List.of(PortfolioItem.createCash(BigDecimal.valueOf(100), "KRW", BigDecimal.valueOf(100), LocalDate.now())));
        portfolio.getItems().forEach(item -> ReflectionTestUtils.setField(item, "createdAt", LocalDateTime.now()));
        
        Portfolio saved = portfolioRepository.save(portfolio);
        portfolioRepository.flush();

        // when
        portfolioRepository.delete(saved);
        portfolioRepository.flush();

        // then
        assertThat(portfolioRepository.findById(saved.getId())).isEmpty();
    }
}
