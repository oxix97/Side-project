package org.stockwellness.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import static org.springframework.http.HttpStatus.*;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 공통 (G)
    INVALID_INPUT_VALUE(BAD_REQUEST, "G001", "잘못된 입력값입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "G002", "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(NOT_FOUND, "G003", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "G004", "서버 내부 오류가 발생했습니다."),

    // 인증/인가 (A)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A002", "접근 권한이 없습니다."),
    EXPIRED_JWT(HttpStatus.UNAUTHORIZED, "A003", "만료된 토큰입니다."),
    INVALID_JWT(HttpStatus.UNAUTHORIZED, "A004", "유효하지 않은 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "A005", "리프레시 토큰이 유효하지 않습니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "A006", "리프레시 토큰을 찾을 수 없습니다."),
    OAUTH2_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "A007", "소셜 로그인에 실패했습니다."),
    REQUIRE_SIGNUP(HttpStatus.UNAUTHORIZED, "A008", "회원가입이 필요한 기능입니다."),
    OAUTH_EXCHANGE_CODE_INVALID(HttpStatus.UNAUTHORIZED, "A009", "OAuth 교환 코드가 유효하지 않습니다."),

    // 회원 (M)
    MEMBER_NOT_FOUND(NOT_FOUND, "M001", "회원을 찾을 수 없습니다."),
    DUPLICATE_EMAIL(CONFLICT, "M002", "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(CONFLICT, "M003", "이미 사용 중인 닉네임입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "M004", "비밀번호가 일치하지 않습니다."),

    // 포트폴리오 (P)
    INVALID_PORTFOLIO_TOTAL_PIECE(BAD_REQUEST, "P001", "포트폴리오 총 조각 수는 1개 이상, 8개 이하여야 합니다."),
    INVALID_ITEM_PIECE_COUNT(BAD_REQUEST, "P002", "각 종목은 최소 1조각 이상이어야 합니다."),
    DUPLICATE_PORTFOLIO_NAME(CONFLICT, "P003", "이미 사용 중인 포트폴리오 이름입니다."),
    PORTFOLIO_NOT_FOUND(NOT_FOUND, "P004", "포트폴리오를 찾을 수 없습니다."),
    PORTFOLIO_ACCESS_DENIED(FORBIDDEN, "P005", "해당 포트폴리오에 대한 접근 권한이 없습니다."),
    UNSUPPORTED_PORTFOLIO_CURRENCY(BAD_REQUEST, "P006", "원화 종목만 가상 포트폴리오에 담을 수 있습니다."),

    // 관심 종목 (W)
    WATCHLIST_GROUP_LIMIT_EXCEEDED(BAD_REQUEST, "W001", "관심 그룹은 최대 10개까지 생성할 수 있습니다."),
    WATCHLIST_ITEM_LIMIT_EXCEEDED(BAD_REQUEST, "W002", "관심 종목은 한 그룹당 최대 50개까지 추가할 수 있습니다."),
    DUPLICATE_WATCHLIST_ITEM(CONFLICT, "W003", "이미 그룹에 등록된 종목입니다."),
    WATCHLIST_NOTE_LIMIT_EXCEEDED(BAD_REQUEST, "W004", "메모는 최대 200자까지 입력할 수 있습니다."),

    // 주식 (S)
    STOCK_NOT_FOUND(NOT_FOUND, "S001", "종목을 찾을 수 없습니다."),
    PRICE_DATA_NOT_FOUND(NOT_FOUND, "S002", "해당 기간의 시세 데이터를 찾을 수 없습니다."),

    // 섹터 (T)
    SECTOR_NOT_FOUND(NOT_FOUND, "T001", "섹터를 찾을 수 없습니다."),
    SECTOR_DATA_NOT_FOUND(NOT_FOUND, "T002", "섹터 상세 정보를 찾을 수 없습니다."),
    SECTOR_HISTORY_NOT_FOUND(NOT_FOUND, "T003", "섹터 이력 데이터를 찾을 수 없습니다."),
    SECTOR_SYNC_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "T004", "섹터 데이터 동기화 중 오류가 발생했습니다."),

    // 배치 (B)
    BATCH_STEP_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "B001", "배치 작업이 중단되었습니다."),
    RATE_LIMIT_WAIT_CANCELLED(HttpStatus.INTERNAL_SERVER_ERROR, "B002", "RateLimiter 대기 중 취소되었습니다."),
    BATCH_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "B003", "배치 실행 중 오류가 발생했습니다."),
    BATCH_ORCHESTRATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "B004", "배치 오케스트레이션 중 오류가 발생했습니다."),
    BATCH_JOB_ALREADY_RUNNING(HttpStatus.CONFLICT, "B005", "동일한 배치 작업이 이미 실행 중입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    public int getStatusCode() {
        return status.value();
    }
}
