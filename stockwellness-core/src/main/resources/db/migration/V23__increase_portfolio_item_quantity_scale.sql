-- 가상 포트폴리오의 EOD 기반 수량은 소수점 여섯 자리까지 보존한다.
ALTER TABLE portfolio_item
    ALTER COLUMN quantity TYPE numeric(19, 6);
