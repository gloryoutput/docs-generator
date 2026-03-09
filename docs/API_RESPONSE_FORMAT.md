# API 표준 응답 형식

Football API는 모든 엔드포인트에서 `ApiResponse<T>` 래퍼를 사용하여 일관된 응답 형식을 제공합니다.

> 제공 모듈: `com.lodong.utils.response.ApiResponse` (`ld-utils-module v1.0.9`)

---

## 1. 응답 구조

### 최상위 필드

| 필드 | 타입 | 설명 | 항상 포함 |
|------|------|------|-----------|
| `success` | `boolean` | 요청 성공 여부 | O |
| `data` | `T` (제네릭) | 응답 데이터 (성공 시) | 성공 시 |
| `error` | `ErrorInfo` | 에러 정보 (실패 시) | 실패 시 |
| `pagination` | `Pagination` | 페이징 정보 (Page 조회 시) | 페이징 시 |

---

## 2. 성공 응답

### 2.1 단건 데이터 응답

```json
{
  "success": true,
  "data": {
    "id_teams": "550e8400-e29b-41d4-a716-446655440000",
    "team_name": "FC Barcelona"
  },
  "error": null,
  "pagination": null
}
```

**사용법**: `ApiResponse.ok(data)`

### 2.2 문자열 메시지 응답

```json
{
  "success": true,
  "data": "로그아웃 되었습니다",
  "error": null,
  "pagination": null
}
```

**사용법**: `ApiResponse.ok("메시지")`

### 2.3 데이터 없는 성공 응답

```json
{
  "success": true,
  "data": null,
  "error": null,
  "pagination": null
}
```

**사용법**: `ApiResponse.ok()`

### 2.4 리스트 응답

```json
{
  "success": true,
  "data": [
    { "id_teams": "...", "team_name": "팀 A" },
    { "id_teams": "...", "team_name": "팀 B" }
  ],
  "error": null,
  "pagination": null
}
```

**사용법**: `ApiResponse.ok(list)`

### 2.5 페이징 응답

`Page<T>` 객체를 전달하면 자동으로 `pagination` 필드가 포함됩니다.

```json
{
  "success": true,
  "data": [
    { "id_teams": "...", "team_name": "팀 A" },
    { "id_teams": "...", "team_name": "팀 B" }
  ],
  "error": null,
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 53,
    "totalPages": 3,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

**사용법**: `ApiResponse.ok(page)`

#### Pagination 필드 상세

| 필드 | 타입 | 설명 |
|------|------|------|
| `page` | `int` | 현재 페이지 번호 (0부터 시작) |
| `size` | `int` | 페이지당 항목 수 |
| `totalElements` | `long` | 전체 항목 수 |
| `totalPages` | `int` | 전체 페이지 수 |
| `hasNext` | `boolean` | 다음 페이지 존재 여부 |
| `hasPrevious` | `boolean` | 이전 페이지 존재 여부 |

---

## 3. 실패 응답

### 3.1 기본 에러 응답

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "TEAM001",
    "message": "팀을 찾을 수 없습니다",
    "path": "/api/teams/550e8400-e29b-41d4-a716-446655440000",
    "fields": null
  },
  "pagination": null
}
```

### 3.2 유효성 검증 실패 응답 (Validation)

`@Valid` 검증 실패 시 필드별 에러 정보가 포함됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "COMMON_VALIDATION",
    "message": "입력값 검증 실패: {team_name=팀명은 필수입니다, level=레벨은 필수입니다}",
    "path": "/api/teams",
    "fields": null
  },
  "pagination": null
}
```

### 3.3 타입 변환 오류 응답

UUID 형식 오류 등 파라미터 타입이 맞지 않을 때 발생합니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "COMMON_VALIDATION",
    "message": "파라미터 'id_teams'의 값 'invalid-uuid'이(가) 올바르지 않습니다. UUID 형식이 필요합니다.",
    "path": "/api/teams/invalid-uuid",
    "fields": null
  },
  "pagination": null
}
```

### 3.4 Enum 변환 오류 응답

잘못된 Enum 값 전달 시 `hint` 필드가 포함될 수 있습니다.

```json
{
  "success": false,
  "code": "COMMON_VALIDATION",
  "message": "입력값이 올바르지 않습니다",
  "detail_message": "No enum constant com.lodong.footballapi.global.enums.UserRole.INVALID",
  "hint": "허용 값: ADMIN, COACH, PLAYER"
}
```

> **참고**: `IllegalArgumentException` 처리는 `ApiResponse` 래퍼를 사용하지 않고 `Map<String, Object>`로 직접 반환됩니다.

### 3.5 접근 거부 응답 (403)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "COMMON_FORBIDDEN",
    "message": "접근 권한이 없습니다",
    "path": "/api/teams",
    "fields": null
  },
  "pagination": null
}
```

### 3.6 서버 내부 오류 응답 (500)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "COMMON_INTERNAL",
    "message": "서버 내부 오류가 발생했습니다",
    "path": "/api/teams",
    "fields": null
  },
  "pagination": null
}
```

---

## 4. ErrorInfo 필드 상세

| 필드 | 타입 | 설명 |
|------|------|------|
| `code` | `String` | 도메인별 에러 코드 (예: `TEAM001`, `AUTH003`) |
| `message` | `String` | 사용자에게 표시할 에러 메시지 |
| `path` | `String` | 에러가 발생한 API 경로 |
| `fields` | `List<FieldError>` | 필드별 유효성 검증 에러 목록 (해당 시) |

### FieldError 구조

| 필드 | 타입 | 설명 |
|------|------|------|
| `field` | `String` | 에러가 발생한 필드명 |
| `value` | `Object` | 전달된 값 |
| `reason` | `String` | 에러 사유 |

---

## 5. HTTP 상태 코드

| HTTP 상태 코드 | 의미 | 발생 상황 |
|---------------|------|-----------|
| `200` | OK | 정상 처리 |
| `400` | Bad Request | 유효성 검증 실패, 타입 변환 오류, 잘못된 입력값 |
| `401` | Unauthorized | 인증 실패, 토큰 만료/무효 |
| `403` | Forbidden | 권한 부족, 비활성화된 사용자 |
| `404` | Not Found | 리소스 없음 |
| `409` | Conflict | 중복 데이터 |
| `500` | Internal Server Error | 서버 내부 오류 |

---

## 6. 에러 코드 체계

에러 코드는 `{도메인}{번호}` 형식으로 구성됩니다.

### 6.1 공통 에러 코드 (`CommonErrorCode`)

| 코드 | 설명 | HTTP 상태 |
|------|------|-----------|
| `COMMON_BAD_REQUEST` | 잘못된 요청 | 400 |
| `COMMON_VALIDATION` | 유효성 검증 실패 | 400 |
| `COMMON_MISSING_PARAMETER` | 필수 파라미터 누락 | 400 |
| `COMMON_INVALID_FORMAT` | 잘못된 형식 | 400 |
| `COMMON_UNAUTHORIZED` | 인증 필요 | 401 |
| `COMMON_INVALID_TOKEN` | 유효하지 않은 토큰 | 401 |
| `COMMON_EXPIRED_TOKEN` | 만료된 토큰 | 401 |
| `COMMON_FORBIDDEN` | 접근 권한 없음 | 403 |
| `COMMON_NOT_FOUND` | 리소스 없음 | 404 |
| `COMMON_DUPLICATE` | 중복 데이터 | 409 |
| `COMMON_DATA_INTEGRITY` | 데이터 무결성 오류 | 409 |
| `COMMON_INTERNAL` | 서버 내부 오류 | 500 |

### 6.2 인증 에러 코드 (`AuthErrorCode`)

| 코드 | 설명 | HTTP 상태 |
|------|------|-----------|
| `AUTH000` | 인증이 필요합니다 | 401 |
| `AUTH001` | 잘못된 인증 정보입니다 | 401 |
| `AUTH002` | 유효하지 않은 토큰입니다 | 401 |
| `AUTH003` | 만료된 토큰입니다 | 401 |
| `AUTH004` | 사용자를 찾을 수 없습니다 | 404 |
| `AUTH005` | 비활성화된 사용자입니다 | 403 |
| `AUTH006` | Refresh Token이 없습니다 | 401 |
| `AUTH007` | 유효하지 않은 Refresh Token입니다 | 401 |

### 6.3 팀 에러 코드 (`TeamErrorCode`)

| 코드 | 설명 | HTTP 상태 |
|------|------|-----------|
| `TEAM001` | 팀을 찾을 수 없습니다 | 404 |
| `TEAM002` | 이미 존재하는 팀입니다 | 409 |

### 6.4 선수 에러 코드 (`PlayerErrorCode`)

| 코드 | 설명 | HTTP 상태 |
|------|------|-----------|
| `PLAYER001` | 선수를 찾을 수 없습니다 | 404 |
| `PLAYER002` | 선수 프로필을 찾을 수 없습니다 | 404 |
| `PLAYER003` | 선수 역할이 아닙니다 | 400 |
| `PLAYER004` | 이미지를 불러올 수 없습니다 | 404 |

### 6.5 멤버 에러 코드 (`MemberErrorCode`)

| 코드 | 설명 | HTTP 상태 |
|------|------|-----------|
| `MEMBER001` | 팀원을 찾을 수 없습니다 | 404 |
| `MEMBER002` | 사용자 정보를 찾을 수 없습니다 | 404 |
| `MEMBER003` | 팀을 찾을 수 없습니다 | 404 |
| `MEMBER004` | 유효하지 않은 역할입니다 | 400 |
| `MEMBER005` | 이미지를 불러올 수 없습니다 | 404 |
| `MEMBER006` | 회원 코드를 찾을 수 없습니다 | 404 |
| `MEMBER007` | 역할을 찾을 수 없습니다 | 404 |
| `MEMBER008` | 팀원 정보를 찾을 수 없습니다 | 404 |
| `MEMBER009` | 이미 사용 중인 이메일입니다 | 409 |

### 6.6 공통 도메인 에러 코드 (`CommonErrorCode` - 도메인)

| 코드 | 설명 | HTTP 상태 |
|------|------|-----------|
| `COMMON001` | 포지션을 찾을 수 없습니다 | 404 |
| `COMMON002` | 연령대를 찾을 수 없습니다 | 404 |
| `COMMON003` | 학업 성적 등급을 찾을 수 없습니다 | 404 |
| `COMMON004` | 영입 상태를 찾을 수 없습니다 | 404 |
| `COMMON005` | 리포트 타입을 찾을 수 없습니다 | 404 |
| `COMMON006` | 국적을 찾을 수 없습니다 | 404 |
| `COMMON007` | 국기 이미지를 찾을 수 없습니다 | 404 |
| `COMMON008` | 국기 이미지 다운로드에 실패했습니다 | 500 |
| `COMMON009` | 출전 유형을 찾을 수 없습니다 | 404 |
| `COMMON010` | 역할을 찾을 수 없습니다 | 404 |
| `COMMON011` | 권한을 찾을 수 없습니다 | 404 |

---

## 7. 예외 처리 흐름

```
Controller → Service에서 예외 발생
                ↓
        ApiExceptionHandler (@RestControllerAdvice)
                ↓
        예외 타입별 핸들러 매칭
                ↓
        HTTP 상태 코드 설정 (HttpServletResponse.setStatus)
                ↓
        ApiResponse.fail() 반환
```

### 처리 우선순위

`@Order(Ordered.HIGHEST_PRECEDENCE)`로 최우선 처리되며, 예외 타입별 매칭 순서:

1. `AuthException` → 인증/인가 에러
2. `ResourceNotFoundException` → 리소스 없음
3. `EntityNotFoundException` → JPA 엔티티 없음
4. `MemberException` → 멤버 모듈 에러
5. `BaseException` → 모든 커스텀 예외의 기반 클래스
6. `MethodArgumentNotValidException` → `@Valid` 유효성 검증 실패
7. `MethodArgumentTypeMismatchException` → 타입 변환 오류
8. `IllegalArgumentException` → Enum 변환 등 잘못된 인자
9. `AccessDeniedException` → Spring Security 권한 부족
10. `Exception` → 기타 모든 예외 (500)

---

## 8. 프론트엔드 연동 가이드

### 성공/실패 판별

```javascript
const response = await fetch('/api/teams');
const result = await response.json();

if (result.success) {
  // 성공: result.data 사용
  const teams = result.data;
} else {
  // 실패: result.error 사용
  console.error(`[${result.error.code}] ${result.error.message}`);
}
```

### 페이징 처리

```javascript
const response = await fetch('/api/teams?page=0&size=20');
const result = await response.json();

if (result.success) {
  const teams = result.data;
  const { page, totalPages, totalElements, hasNext } = result.pagination;
}
```

### 에러 코드 기반 분기 처리

```javascript
if (!result.success) {
  switch (result.error.code) {
    case 'AUTH003':
      // 토큰 만료 → 리프레시 토큰으로 갱신
      break;
    case 'COMMON_FORBIDDEN':
      // 권한 부족 → 접근 제한 안내
      break;
    default:
      // 일반 에러 메시지 표시
      alert(result.error.message);
  }
}
```
