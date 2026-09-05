# Workflow

## 적용 범위와 승인

AGENTS.md의 역할·자율성·승인·완료 정책을 따른다. 아래 라이프사이클은 해당 행위가 요청된 경우의 순서이며 실행 권한을 부여하지 않는다. Issue가 없는 요청은 승인된 구현·검증을 먼저 진행하고 Issue 초안을 준비할 수 있다. GitHub 등록·종료, PR·머지·push·배포는 명시적으로 요청된 범위에서만 수행한다. 커밋은 정확한 메시지와 포함 파일의 승인을 받은 뒤 실행한다.

## 작업 라이프사이클

1. 사용자 요청 범위를 확인하고 연결된 GitHub Issue가 있으면 확인 (`gh issue list --state open`)
2. 브랜치 생성: `feature/#<이슈번호>-<설명>`
3. 동작 변경·버그 수정은 실패하는 테스트 작성 (Red); 비동작 변경은 개발 스킬의 적합한 검증 적용
4. 테스트를 통과하는 최소 구현 (Green)
5. 필요한 리팩터링 후 신규 코드 커버리지 확인 (관련 코드 변경 시 80% 이상)
6. 커밋 메시지는 **한글**로 작성, 커밋 전 메시지 보고 후 승인 받고 진행
7. PR → 코드 리뷰 → `develop` 머지 (`--no-ff`)

## 핵심 원칙

- **작업 추적**: 연결된 GitHub Issue가 있으면 기준으로 사용한다. 없으면 현재 요청과 로컬 초안으로 범위를 추적하며 Issue 등록을 구현의 선행 승인 조건으로 만들지 않는다.
- **기술 스택 변경 시 선 문서화**: `docs/tech-stack.md` 먼저 업데이트 후 구현
- **Non-Interactive 명령 선호**: watch 모드 도구는 `CI=true` 플래그 사용

## 개발 명령어

```bash
# 인프라 기동
docker compose up -d

# API 서버 실행
./gradlew :stockwellness-api:bootRun

# 배치 서버 실행
./gradlew :stockwellness-batch:bootRun

# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :stockwellness-api:test
./gradlew :stockwellness-core:test

# REST Docs 생성
./gradlew :stockwellness-api:openapi3
```

## 완료 기준 (Definition of Done)

- [ ] 테스트 통과
- [ ] 테스트 규칙에 따른 신규 코드 커버리지 80% 이상 (관련 코드 변경 시)
- [ ] 커스텀 예외 사용 (Raw Exception 금지)
- [ ] REST Docs 문서화 (API 변경 시)
- [ ] 변경 범위의 구현·검증 증거와 미검증 항목 보고

커밋·Issue 종료·PR 머지는 구현 완료와 별도 상태로 보고한다. 요청되지 않은 행위는 완료 조건이 아니며, 요청된 후속 행위가 남으면 전체 작업은 미완료다. 커밋이 요청되면 한글 메시지 컨벤션과 AGENTS.md의 승인을 따른다.

## 배포

```bash
# API 서버
./deploy/scripts/deploy-api.sh

# 배치 서버
./deploy/scripts/deploy-batch.sh
```

환경 변수: `deploy/.env` (`.env.example` 참고)
