---
name: backend-development
description: stockwellness 백엔드 저장소에서 Java, Spring Boot, REST API, 배치, 영속성, Kafka, Redis 또는 금융 도메인 동작을 구현하거나 변경할 때 사용한다.
---

# 백엔드 개발

## 개요

도메인 소유권, 모듈 의존 방향, API 계약과 금융 데이터 의미를 보존하면서 필요한 최소 변경을 구현한다. 운영 코드를 작성하기 전에 실패하는 테스트로 동작을 증명한다.

## 작업 절차

1. `AGENTS.md`, 기준 명세, 관련 도메인 코드, 어댑터와 테스트를 읽는다.
2. `git status`를 확인하고 관련 없는 사용자 변경을 보호한다. 요청 범위로 수정 파일을 제한한다.
3. 코드를 작성하기 전에 소유권을 정한다.
   - 도메인 규칙과 상태 전이는 `stockwellness-core` 도메인 모델에 둔다.
   - 흐름 조율은 애플리케이션 서비스 또는 `PortfolioFacade`에 둔다.
   - `stockwellness-api`와 `stockwellness-batch`는 얇은 입력 어댑터로 유지한다.
   - `core -> api`, `core -> batch`, `api <-> batch` 의존성을 만들지 않는다.
4. 관찰 가능한 동작과 예외 상황을 정의한다. 금융 동작에는 기준일, 단위, 정밀도, 반올림, 누락 데이터, 0과 음수 처리 방식을 명시한다.
5. 하나의 동작에 집중한 실패 테스트를 작성한다. 설정이나 문법 문제가 아니라 동작 부재 때문에 실패하는지 확인한다.
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
| 모듈 간 변경·최종 확인 | `./gradlew clean build` |

`<test-class>`는 저장소에서 선택한 정확한 테스트로 바꾸며, 자리표시자 명령을 그대로 실행하지 않는다.

## 결과 형식

다음을 보고한다.

1. 구현한 동작과 소유 모듈
2. 예외 상황을 포함한 금융·API 결정
3. 구현 전에 확인한 실패 테스트
4. 검증 명령과 결과
5. 남은 위험 또는 명시적으로 제외한 범위

## 예시

“포트폴리오 분석에 `riskLevel` 추가” 작업은 먼저 도메인에 enum과 경계 규칙을 정의하고, 경계값과 가격 누락 동작을 테스트한다. 이후 `record` DTO로 노출하고 REST Docs/OpenAPI를 수정한 뒤 API 모듈을 확인한다. 컨트롤러에서만 위험도를 계산하거나 계약 테스트를 생략하지 않는다.

## 흔한 실수

- 비즈니스 규칙을 컨트롤러, 배치 단계 또는 조율 서비스에 둔다.
- 의미 있는 테스트 실패를 확인하기 전에 운영 코드를 작성한다.
- H2 테스트 통과를 PostgreSQL 전용 쿼리의 정확성 증거로 판단한다.
- REST Docs/OpenAPI와 소비자 영향을 확인하지 않고 응답을 변경한다.
- 요청과 관계없는 포맷 정리나 리팩터링을 같은 diff에 섞는다.
