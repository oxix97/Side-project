---
name: backend-development
description: Use when implementing Java, Spring Boot, REST API, batch, persistence, or financial-domain changes in the stockwellness backend.
---

# 백엔드 개발

## 개요

도메인 소유권, 모듈 의존 방향, API 계약과 금융 데이터 의미를 보존하면서 필요한 최소 변경을 구현한다. 동작 변경과 버그 수정은 운영 코드 작성 전에 의미 있는 실패 테스트로 검증한다.

동작에 영향을 주지 않는 문서·표현·생성물 변경은 적합한 정적·시각·생성 검증을 수행한다. 단순히 파일 종류만으로 동작 변경을 제외하지 않는다. 다음 실패 테스트 절차는 동작 변경·버그 수정에 적용한다. 금융·인증·API 동작과 사용자가 지정한 검증은 생략하지 않는다. 적용되는 스킬에 별도의 명시적 예외 승인이 있으면 AGENTS.md의 기존 승인 재사용 규칙을 따른다.

## 작업 절차

1. `AGENTS.md`, 기준 명세, 관련 도메인 코드, 어댑터와 테스트를 읽는다. 작업에 필요한 문서만 추가로 읽는다.
   - API·코드 패턴: `docs/code-style.md`
   - 테스트 전략: `docs/testing.md`
   - 작업 흐름·실행 명령: `conductor/workflow.md`
   - 기술 버전·모듈: `build.gradle.kts`, `settings.gradle.kts`; 문서의 고정 버전보다 빌드 파일을 우선한다.
   - 로컬 인프라: `compose.yaml`과 실제 애플리케이션 설정을 확인하고 `.env` 값은 출력하지 않는다.
2. `git status`를 확인하고 관련 없는 사용자 변경을 보호한다. 요청 범위로 수정 파일을 제한한다.
3. 코드를 작성하기 전에 소유권을 정한다.
   - 도메인 규칙과 상태 전이는 `stockwellness-core` 도메인 모델에 둔다.
   - 흐름 조율은 애플리케이션 서비스 또는 `PortfolioFacade`에 둔다.
   - `stockwellness-api`와 `stockwellness-batch`는 얇은 입력 어댑터로 유지한다.
   - `core -> api`, `core -> batch`, `api <-> batch` 의존성을 만들지 않는다.
4. 관찰 가능한 동작과 예외 상황을 정의한다. 금융 동작에는 기준일, 단위, 정밀도, 반올림, 누락 데이터, 0과 음수 처리 방식을 명시한다.
5. 하나의 동작에 집중한 실패 테스트를 먼저 작성하고 실행한다. 설정이나 문법 문제가 아니라 동작 부재 때문에 실패하는지 확인한 뒤에만 운영 코드를 작성한다.
6. 테스트를 통과하는 최소 코드를 구현한다. 인라인 완전 수식 클래스명 대신 import를 사용하고 DTO, Command, Event는 Java `record`로 작성한다.
7. 표준 응답 래퍼와 `GlobalException(ErrorCode)` 오류 경로를 유지한다. 원시 예외나 비밀값을 노출하지 않는다.
8. API 계약이 바뀌면 Spring REST Docs 테스트를 수정하고 OpenAPI 결과를 갱신하거나 확인한다. 프론트엔드 호환성도 변경 범위에 포함한다.
9. 가장 좁은 관련 테스트부터 실행하고 영향받은 모듈 테스트를 실행한다. 모듈 간 변경이나 출시 관련 변경에는 전체 빌드를 실행한다.
10. diff에서 의도하지 않은 파일, 트랜잭션 경계 변경, 쿼리 동작과 EOD·실시간 표현을 확인한다.

## 검증 단계

| 변경 범위 | 최소 명령 |
|---|---|
| 코어 도메인 | `./gradlew :stockwellness-core:test --tests "<test-class>"` |
| API 어댑터·계약 | `./gradlew :stockwellness-api:test --tests "<test-class>"` |
| 배치 동작 | `./gradlew :stockwellness-batch:test --tests "<test-class>"` |
| 모듈 간 변경 또는 출시 검증 | `./gradlew clean build` |

`<test-class>`는 저장소에서 선택한 정확한 테스트로 바꾸며, 자리표시자 명령을 그대로 실행하지 않는다.

## 결과 형식

다음을 보고한다.

1. 구현한 동작과 소유 모듈
2. 예외 상황을 포함한 금융·API 결정
3. 동작 변경의 실패 테스트 또는 비동작 변경에 선택한 검증과 근거
4. 검증 명령과 결과
5. 남은 위험 또는 명시적으로 제외한 범위

## 흔한 실수

- 비즈니스 규칙을 컨트롤러, 배치 단계 또는 조율 서비스에 둔다.
- 동작 변경에서 의미 있는 테스트 실패를 확인하기 전에 운영 코드를 작성한다.
- H2 테스트 통과를 PostgreSQL 전용 쿼리의 정확성 증거로 판단한다.
- REST Docs/OpenAPI와 소비자 영향을 확인하지 않고 응답을 변경한다.
- 요청과 관계없는 포맷 정리나 리팩터링을 같은 diff에 섞는다.
