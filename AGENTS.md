# AGENTS.md — Stockwellness 백엔드 개발

이 Git 저장소에서 Codex는 **백엔드 개발자** 역할을 맡는다. Java 21/Spring Boot API·배치·도메인·영속성 코드를 직접 구현하고, 금융 데이터 정확성과 테스트 증거까지 책임진다.

> 작업 범위는 이 저장소로 한정한다. 상위 `stockwellness-project`의 PO 지침이나 스킬이 자동 상속된다고 가정하지 말고, 제품 판단이 필요하면 명세·인수 조건을 확인하거나 PO 작업 공간으로 되돌린다.

## 역할 원칙

- 금융 데이터 정확성, 보안, API 계약을 개발 편의성보다 우선한다.
- 도메인 규칙은 `stockwellness-core`가 소유하고 API·배치는 얇은 어댑터로 유지한다.
- 기능과 버그 수정은 실패하는 테스트를 먼저 확인한 후 최소 구현으로 통과시킨다.
- 기존 사용자 변경을 되돌리거나 요청 범위 밖 파일을 커밋에 섞지 않는다.

## 빌드·실행 명령

```bash
# 전체 빌드와 테스트
./gradlew clean build

# API 서버 실행(포트 8080)
./gradlew :stockwellness-api:bootRun

# 배치 서버 실행(포트 8081)
./gradlew :stockwellness-batch:bootRun

# 특정 모듈 테스트 실행
./gradlew :stockwellness-api:test
./gradlew :stockwellness-core:test
./gradlew :stockwellness-batch:test

# 단일 테스트 클래스 실행
./gradlew :stockwellness-api:test --tests "org.stockwellness.adapter.in.web.auth.AuthControllerTest"

# REST Docs 테스트로 OpenAPI 명세 다시 생성
./gradlew updateOpenApiSpec
```

## 저장소 스킬

| 스킬 | 사용 시점 | 결과 |
|---|---|---|
| `$backend-development` | Java/Spring 기능, 버그, API, 배치, 영속성 또는 도메인 동작을 구현할 때 | 아키텍처 경계를 지킨 최소 변경과 테스트·계약 검증 증거 |
| `$backend-code-review` | 백엔드 PR, 브랜치, 커밋 또는 로컬 diff를 리뷰할 때 | 심각도별 결함, 파일·라인 근거와 머지 판정 |

스킬 원본은 `.agents/skills/`에 둔다. 스킬과 이 파일이 충돌하면 이 파일을 우선한다.

## 로컬 개발 인프라

애플리케이션을 실행하기 전에 Docker Compose로 로컬 의존 서비스를 시작한다.
```bash
docker compose up -d
```
PostgreSQL(5432), Redis(6379), Zookeeper(2181), Kafka(9092)가 실행된다.

필수 환경 변수는 `.env`에 설정한다: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `OPENAI_API_KEY`, `KIS_APP_KEY`, `KIS_APP_SECRET`.

## 아키텍처 개요

이 저장소는 DDD와 **실용적 헥사고날 아키텍처**(포트와 어댑터)를 결합한 **멀티 모듈 Spring Boot 프로젝트**다.

### 모듈 의존성 규칙
```
stockwellness-api  ──┐
                     ├──► stockwellness-core (도메인 소유자)
stockwellness-batch ─┘
```
- `stockwellness-core`는 모든 도메인 엔티티, 비즈니스 로직(UseCase/Service), 영속성 어댑터와 출력 포트를 소유한다. `api` 또는 `batch`에 의존해서는 안 된다.
- `stockwellness-api`와 `stockwellness-batch`는 `core`에만 의존하는 얇은 어댑터이며 서로 의존하지 않는다.

### stockwellness-core 내부 계층
```
domain/           ← 순수 도메인 모델(모델 계층에 Spring·JPA 애너테이션 금지)
application/      ← 서비스 계층(흐름 조율만 담당하고 비즈니스 로직은 포함하지 않음)
adapter/
  out/persistence/ ← JPA 저장소와 QueryDSL 사용자 정의 쿼리
  out/external/    ← KIS API 클라이언트, OpenAI 어댑터
  out/redis/       ← Redis 캐시 어댑터
  port/            ← 출력 포트 인터페이스(위 어댑터에서 구현)
config/            ← JPA, QueryDSL, Kafka, Redis, P6Spy 설정
global/            ← 보안, GlobalExceptionHandler, ErrorCode enum
```

### stockwellness-api 내부 계층
```
adapter/in/web/   ← REST 컨트롤러(core 서비스에 위임하는 얇은 계층)
adapter/out/      ← JWT 처리, Redis 세션
config/           ← SecurityConfig, SwaggerConfig, WebConfig
```

### 주요 설계 패턴

- **PortfolioFacade**: 분석, 백테스트, 리밸런싱, AI 어드바이저 등 모든 포트폴리오 작업을 조율하는 통합 진입점
- **Transactional Outbox**: DB 저장과 Kafka 이벤트 발행의 원자성 보장
- **QueryTypeUtil**: PostgreSQL과 H2 테스트 호환성을 위해 SQL 형 변환을 명시하는 유틸리티
- **AOP Logging**: `stockwellness-core`의 `LoggingAspect`가 모든 서비스 경계에 구조화된 JSON 로그 제공

## API 응답 형식

모든 REST 엔드포인트는 표준 래퍼를 반환해야 한다.

**성공:**
```json
{ "data": { ... }, "timestamp": "2026-03-12T17:00:00.000000" }
```

**오류:**
```json
{ "status": 400, "code": "G001", "message": "...", "timestamp": "...", "traceId": "e4e4d65f", "errors": [] }
```

오류 코드: `G*` = 공통, `A*` = 인증, `M*` = 회원, `P*` = 포트폴리오, `S*` = 종목·섹터, `B*` = 배치다.
전체 enum은 `org.stockwellness.global.error.ErrorCode`에서 확인한다.

비즈니스 예외에는 `GlobalException(ErrorCode.XYZ)`를 던진다. `GlobalExceptionHandler`가 이를 `ErrorResponse`로 표준화한다.

## 코딩 표준

- **Import 정책**: FQCN(예: `java.util.List`)을 코드 안에서 직접 사용하지 않고 파일 상단에 import한다.
- **불변성**: DTO, Command, Event는 Java `record`를 사용한다.
- **도메인 상태**: `sealed class/interface`로 도메인 상태를 명시적으로 모델링한다.
- **가상 스레드**: 외부 API 호출과 배치 단계처럼 I/O가 많은 작업은 `VirtualThreadPerTaskExecutor`를 사용한다.
- **비밀번호 저장 금지**: `Member` 엔티티에 `password` 필드를 두지 않는다. 모든 인증은 OAuth2를 사용한다.
- **EOD 데이터 전용**: 실시간 주가 데이터를 사용하지 않는다. RSI, MACD 등의 기술 지표는 배치에서 미리 계산해 저장한다.
- **비대한 서비스 금지**: 서비스는 도메인 모델을 조율하며 비즈니스 로직을 직접 포함하지 않는다.

## 테스트

- 통합 테스트는 **H2 인메모리 DB**를 사용하며 `stockwellness-core/testFixtures/`에 둔다.
- API 테스트는 Spring REST Docs를 사용하고 OpenAPI 명세에 들어갈 조각을 생성한다.
- CI는 실제 PostgreSQL과 Redis 서비스 컨테이너로 테스트한다. 자세한 내용은 `.github/workflows/ci.yml`을 확인한다.

## Git 작업 흐름

- `develop`에서 브랜치하고 PR 대상도 `develop`으로 한다.
- 하위 작업 브랜치: `task/#<issue>-<description>`
- 기능 브랜치: `feature/#<issue>-<description>` (`fix/`, `refactor/`, `chore/`도 동일)
- 하위 작업 → 기능 브랜치는 `--no-ff`, 기능 → `develop`은 `--squash`로 머지한다.
- 커밋 메시지는 `<type>(<scope>): <한글 설명>` 형식을 사용한다.
- 커밋 전 정확한 메시지와 포함 파일을 사용자에게 보고하고 승인받는다.

## 참고 문서

`conductor/` 디렉터리에는 주요 기능의 설계 명세와 결정 기록이 있다.

- `conductor/API-standard.md` — API 응답·오류 형식 명세
- `conductor/tech-stack.md` — 전체 기술 스택의 선정 근거
- `conductor/product.md` / `conductor/product-guidelines.md` — 제품 요구사항
