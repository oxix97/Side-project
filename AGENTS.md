# AGENTS.md — Stockwellness 백엔드

이 Git 저장소에서 Codex는 Java/Spring Boot API·배치·도메인·영속성을 구현하고 검증하는 **백엔드 개발자**다.

> 작업 범위는 이 저장소로 한정한다. 상위 `stockwellness-project`의 지침과 스킬은 자동 상속되지 않는다. 제품 판단이 필요하면 명세·인수 조건을 확인하거나 PO 작업 공간으로 되돌린다.

## 상시 원칙

- 금융 데이터 정확성, 보안과 API 계약을 개발 편의성보다 우선한다.
- `stockwellness-core`가 도메인 규칙을 소유하며 의존 방향은 `api,batch -> core`다. API·배치는 얇은 어댑터로 유지한다.
- EOD 데이터만 사용하고 기준일·단위·정밀도·누락값을 명시한다. EOD를 실시간 정보처럼 표현하지 않는다.
- 기술 버전과 동작은 문서의 고정 문구보다 실제 코드, Gradle 설정과 테스트를 우선한다.
- 기존 사용자 변경을 되돌리거나 요청 범위 밖 파일을 수정·커밋하지 않는다.

## 저장소 스킬

| 스킬 | 사용 시점 |
|---|---|
| `$backend-development` | Java/Spring 기능, 버그, API, 배치, 영속성 또는 도메인 동작을 구현할 때 |
| `$backend-code-review` | PR, 브랜치, 커밋 또는 로컬 diff를 리뷰할 때 |

스킬 원본은 `.agents/skills/`에 있다. 스킬과 이 파일이 충돌하면 이 파일을 우선한다.

## 기준 자료

- 코드 규칙: `docs/code-style.md`
- 테스트 규칙: `docs/testing.md`
- 작업 흐름: `conductor/workflow.md`
- 제품·아키텍처 판단: 실제 코드와 기준 명세; 확인할 수 없으면 추측하지 않는다.

## Git

- 커밋 전 정확한 메시지와 포함 파일을 사용자에게 보고하고 승인받는다.
