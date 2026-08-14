DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sector_indicator
        GROUP BY base_date, sector_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'sector_indicator 자연키 중복을 먼저 정리해야 합니다';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM sector_weather
        GROUP BY base_date, sector_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'sector_weather 자연키 중복을 먼저 정리해야 합니다';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM market_weather
        GROUP BY base_date, market_type
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'market_weather 자연키 중복을 먼저 정리해야 합니다';
    END IF;
END $$;

DROP INDEX IF EXISTS idx_sector_indicator_base_date_code;
DROP INDEX IF EXISTS idx_sector_weather_base_date_code;
DROP INDEX IF EXISTS idx_market_weather_base_date_type;

CREATE UNIQUE INDEX uk_sector_indicator_base_date_code
    ON sector_indicator(base_date, sector_code);

CREATE UNIQUE INDEX uk_sector_weather_base_date_code
    ON sector_weather(base_date, sector_code);

CREATE UNIQUE INDEX uk_market_weather_base_date_type
    ON market_weather(base_date, market_type);
