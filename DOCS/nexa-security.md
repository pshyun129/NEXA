# nexa-security — 토큰 검증 공통 라이브러리

각 서비스가 user-service 가 발급한 access 토큰을 같은 규칙으로 검증하도록 만든 Spring Boot 라이브러리.
의존성만 추가하면 자동 설정으로 등록되고, 서비스는 경로별 권한만 작성한다.

- 소스: `Backend/nexa-security`
- 좌표: `com.ssafy.nexa:nexa-security:1.1.0` (최신)
- 저장소: 이 프로젝트의 GitLab Package Registry (익명 pull 허용, 토큰 불필요)
- 적용 서비스: approval, attendance, leave, payroll (2026-11-02 기준 모두 `1.0.0` 사용 중 → 1.1.0 으로 올리기 필요)

## 버전 기록

| 버전 | 변경 | 기존 서비스 영향 |
|---|---|---|
| 1.1.0 | `AuthUser` 에 `deptId()`, `gradeId()`, `dutyId()` 추가 (access 토큰의 소속 ID 클레임) | 없음. 버전만 올리면 됨. 기존 생성자 `AuthUser(employeeId, role)` 도 유지 |
| 1.0.0 | 최초 배포 | — |

## 인증 흐름

```
브라우저 ──(accessToken 쿠키)──▶ gateway ──(Authorization: Bearer)──▶ 서비스 ──(Bearer 그대로)──▶ 다른 서비스
                               서명·만료·블랙리스트 검사            nexa-security 로 다시 검증
```

- gateway 가 쿠키의 토큰을 검증하고(로그아웃 블랙리스트 포함) Bearer 헤더로 바꿔 넘긴다.
- 각 서비스는 **Bearer 헤더만** 읽는다. 쿠키는 읽지 않는다.
- 서비스 간 호출은 요청한 사원의 토큰을 그대로 넘기고, 받는 서비스가 다시 검증한다.
- 블랙리스트(Redis) 검사는 gateway 에서만 한다. 라이브러리는 Redis 에 의존하지 않는다.

## 제공 기능

| 구성 | 설명 |
|---|---|
| `JwtDecoder` (자동 등록) | user-service JWKS 공개키로 RS256 서명 확인, 만료(오차 0), `type=access`, `sub` 는 양수 사원 ID |
| `NexaSecurityConfigurer` | 세션 없음, CSRF/폼/Basic 로그인 끔, Bearer 토큰 인증, `permit-urls` 열기, 401/403 응답 연결 |
| `@CurrentUser AuthUser` | 컨트롤러에서 요청한 사원 (`employeeId()`, `role()`, `isAdmin()`, 1.1.0부터 `deptId()`, `gradeId()`, `dutyId()`) |
| `@CurrentEmployeeId Long` | 컨트롤러에서 요청한 사원 ID만 |
| 401/403 응답 | 공통 응답 형식으로 직접 작성 (아래 표) |
| `BearerTokenRelayInterceptor` (자동 등록) | 다른 서비스 호출 시 현재 토큰을 Bearer 로 전달 |
| 권한 변환 | `role` 클레임 `USER`/`ADMIN` → `ROLE_USER`/`ROLE_ADMIN` (`hasRole("ADMIN")` 로 검사) |

자동 등록 빈은 모두 `@ConditionalOnMissingBean` 이라, 서비스가 같은 타입의 빈을 만들면 그쪽을 쓴다.
`SecurityFilterChain` 은 만들지 않는다 (서비스마다 경로 권한이 다르므로).

### 인증 실패 응답

`{"success":false,"error":{"code":"...","message":"..."},"data":null}` 형식. 각 서비스 `ErrorCode` 의 AUTH_* 와 같다.

| 상황 | 상태 | 코드 |
|---|---|---|
| 토큰 없음 | 401 | `AUTH_001` 인증이 필요합니다. |
| 만료 | 401 | `AUTH_002` 토큰이 만료되었습니다. |
| 서명 오류, refresh 토큰, 사원 ID 오류 등 | 401 | `AUTH_003` 유효하지 않은 토큰입니다. |
| 토큰은 맞는데 권한 부족 | 403 | `AUTH_009` 접근 권한이 없습니다. |

## 적용 방법

### 1. build.gradle

```groovy
repositories {
	mavenCentral()
	// 팀 공통 라이브러리(nexa-security). 이 저장소의 GitLab Package Registry (익명 pull 허용)
	maven { url = 'https://lab.ssafy.com/api/v4/projects/1457109/packages/maven' }
}

dependencies {
	// spring-boot-starter-security, oauth2-resource-server 가 함께 들어온다
	implementation 'com.ssafy.nexa:nexa-security:1.1.0'
}
```

이미 `spring-boot-starter-oauth2-resource-server` 가 있다면 지운다 (라이브러리가 포함).

### 2. application.properties

```properties
nexa.security.jwks-uri=${JWT_JWKS_URI:http://localhost:8081/.well-known/jwks.json}
```

| 키 | 기본값 | 설명 |
|---|---|---|
| `nexa.security.jwks-uri` | `http://localhost:8081/.well-known/jwks.json` | user-service JWKS 주소. 처음 토큰을 검증할 때 받아온다 |
| `nexa.security.permit-urls` | `/actuator/health`, `/error` | 토큰 없이 열 경로. 지정하면 기본값을 **대체**하므로 두 경로도 함께 적는다 |

Docker(compose) 에서는 `JWT_JWKS_URI: http://user-service:8081/.well-known/jwks.json` 환경변수를 넘긴다.

### 3. SecurityConfig

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
        return nexa.apply(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/xxx/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .build();
    }
}
```

- `nexa.apply(http)` 를 **먼저** 호출하고 경로 권한을 이어서 작성한다 (`permit-urls` 가 앞에 등록되고 `anyRequest()` 는 마지막이어야 함).
- CORS 등 서비스 고유 설정은 `apply` 뒤에 그대로 추가하면 된다.

### 4. 컨트롤러

```java
@GetMapping("/me")
public ApiResult<...> me(@CurrentEmployeeId Long employeeId) { ... }

@GetMapping("/{id}")
public ApiResult<...> get(@PathVariable Long id, @CurrentUser AuthUser user) {
    if (!user.isAdmin() && !user.employeeId().equals(ownerId)) { ... }
}
```

### 5. 기존 코드 정리

라이브러리가 대신하므로 서비스에 있던 아래 코드는 지운다.

- `JwtDecoder` 빈, access 토큰 검증 로직
- `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`
- 요청자 타입(`Requester` 등)과 `@AuthenticationPrincipal` 메타 애노테이션
- 토큰 전달 인터셉터

## 사용 예시

### 예시 1. 요청한 사원 ID 받기 (attendance-service)

헤더로 임시 처리하던 사원 ID를 토큰에서 꺼낸다. 클라이언트가 다른 사람 ID를 넣어 보낼 수 없게 된다.

```java
// 변경 전
@PostMapping("/clock-in")
public ResponseEntity<ApiResult<AttendanceStatusResponse>> clockIn(@RequestHeader("X-User-Id") Long empId) {
    return ResponseEntity.ok(ApiResult.success(attendanceService.clockIn(empId)));
}

// 변경 후
@PostMapping("/clock-in")
public ResponseEntity<ApiResult<AttendanceStatusResponse>> clockIn(@CurrentEmployeeId Long empId) {
    return ResponseEntity.ok(ApiResult.success(attendanceService.clockIn(empId)));
}
```

### 예시 2. 관리자 전용 경로

경로 단위로 막을 수 있으면 SecurityConfig 에서 처리한다. 일반 사원이 오면 403 `AUTH_009`.

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
    return nexa.apply(http)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/attn/admin/**").hasRole("ADMIN")
                    // 메서드별로 나눌 수도 있다: 조회는 모두, 쓰기는 관리자만
                    .requestMatchers(HttpMethod.POST, "/api/v1/employees/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .build();
}
```

### 예시 3. 본인 또는 관리자만 (approval-service)

데이터를 봐야 판단할 수 있는 권한은 `AuthUser` 를 서비스 계층으로 넘겨서 검사한다.

```java
// 컨트롤러
@GetMapping("/{documentId}")
public ResponseEntity<ApiResult<DocumentInfoResponse>> getDocumentInfo(@CurrentUser AuthUser requester,
                                                                      @PathVariable Long documentId) {
    return ResponseEntity.ok(ApiResult.success(documentService.findById(documentId, requester)));
}

// 서비스: 관리자, 기안자, 결재선 참여자만 조회. 권한이 없으면 문서 존재도 숨기도록 404
public Document getReadableDocument(Long documentId, AuthUser requester) {
    Document document = getDocument(documentId);
    if (requester.isAdmin() || document.isDraftedBy(requester.employeeId())) {
        return document;
    }
    if (!approvalLineRepository.existsByDocumentDocIdAndEmpId(documentId, requester.employeeId())) {
        throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
    }
    return document;
}
```

### 예시 4. 컨트롤러 밖에서 현재 사원 꺼내기 (leave-service)

파라미터로 넘기기 어려운 곳에서는 SecurityContext 에서 직접 꺼낸다.
leave-service 의 `EmployeeAccess` 를 토큰 기반으로 구현하면 이렇게 된다.

```java
@Component
public class JwtEmployeeAccess implements EmployeeAccess {

    @Override
    public Long currentEmployeeId() {
        return currentUser().employeeId();
    }

    @Override
    public boolean isAdmin(Long employeeId) {
        AuthUser user = currentUser();
        return user.employeeId().equals(employeeId) && user.isAdmin();
    }

    private AuthUser currentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
            return AuthUser.from(auth.getToken());
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
}
```

`JwtAuthenticationToken` 은 `org.springframework.security.oauth2.server.resource.authentication` 패키지의 것이다.
기존 `DevEmployeeAccess`, `UnavailableEmployeeAccess` 와 빈이 겹치지 않게 프로필을 정리한다.

### 예시 5. 소속(부서·직급·직책)으로 권한 판단 (1.1.0)

access 토큰에 요청한 사원의 **부서 ID, 직급 ID, 직책 ID**가 들어 있어 user-service 를 부르지 않고 꺼낼 수 있다.

```java
@GetMapping("/teams/{deptId}/requests")
public ApiResult<...> teamRequests(@PathVariable Long deptId, @CurrentUser AuthUser user) {
    // 관리자이거나 같은 부서 사원만
    if (!user.isAdmin() && !deptId.equals(user.deptId())) {
        throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }
    ...
}
```

| 메서드 | 클레임 | 내용 |
|---|---|---|
| `deptId()` | `deptId` | 부서 ID |
| `gradeId()` | `gradeId` | 직급 ID |
| `dutyId()` | `dutyId` | 직책 ID (팀장 등) |

주의할 것

- **null 일 수 있다.** user-service 가 소속 클레임을 넣기 전에 발급된 토큰이거나 소속 정보가 없는 사원이면 null. `Long` 비교는 `deptId.equals(user.deptId())` 처럼 null 이 아닌 쪽에서 호출한다.
- **ID 만 있다. 이름은 없다.** 부서명·직급명은 바뀔 수 있어 토큰에 넣지 않았다. 권한은 ID 로 비교하고, 화면 표시용 이름은 user-service 에서 받는다 (직책명: `GET /api/v1/duties/{dutyId}`).
- **최대 15분 늦게 반영된다.** 소속은 토큰을 발급할 때의 값이다. 부서 이동·직책 변경은 access 토큰이 재발급될 때(최대 15분) 반영된다. 즉시 반영이 꼭 필요한 동작은 user-service 에 실시간으로 확인한다.
- **`gradeId` 크기로 서열을 비교하지 않는다.** ID 는 서열 순서로 매겨져 있다는 보장이 없다. "과장 이상" 같은 판단은 user-service 의 직급 정보로 한다.
- **어떤 `dutyId` 가 팀장인지 아직 코드로 구분할 수 없다.** 직책에 코드·서열 필드가 아직 없어 `dutyId == 3` 같은 하드코딩이 필요해진다. 직책 기준 권한이 필요하면 user-service 담당자와 먼저 상의한다.

테스트에서는 `jwt()` 에 클레임을 함께 넣는다.

```java
mvc.perform(get("/api/v1/xxx").with(jwt().jwt(j -> j.subject("7").claim("type", "access").claim("role", "USER")
        .claim("deptId", 12L).claim("gradeId", 3L).claim("dutyId", 5L))));
```

### 예시 6. 토큰 없이 열 경로 추가

웹훅처럼 토큰 없이 받아야 하는 경로가 있으면 properties 에 추가한다. 기본값을 대체하므로 기본 경로도 함께 적는다.

```properties
nexa.security.permit-urls=/actuator/health,/error,/api/v1/webhooks/**
```

## 다른 서비스 호출 (토큰 전달)

`BearerTokenRelayInterceptor` 빈을 RestClient 에 붙이면, 현재 요청을 인증한 토큰이 `Authorization: Bearer` 로 넘어간다.
인증되지 않은 스레드(스케줄러, 비동기)에서는 헤더를 붙이지 않는다.

```java
@Bean
UserServiceClient userServiceClient(@LoadBalanced RestClient.Builder builder,
                                    BearerTokenRelayInterceptor bearerTokenRelay) {
    RestClient restClient = builder.clone()
            .baseUrl("http://user-service")
            .requestInterceptor(bearerTokenRelay)
            .build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
            .build()
            .createClient(UserServiceClient.class);
}
```

호출할 API 는 인터페이스로 선언한다. 필요한 필드만 받고 모르는 필드는 무시해야 상대 서비스가 응답을 늘려도 깨지지 않는다.

```java
@HttpExchange("/api/v1/employees")
public interface UserServiceClient {

    /** 여러 사원을 한 번에 조회. 없는 ID는 결과에서 빠진다 */
    @GetExchange
    EmployeeListResult getEmployees(@RequestParam("ids") List<Long> ids);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmployeeListResult(EmployeeList data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmployeeList(List<EmployeeStatus> employees) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmployeeStatus(Long empId, String status) {
    }
}
```

서비스에서는 주입받아 일반 메서드처럼 호출한다. 토큰은 인터셉터가 붙인다.

```java
List<EmployeeStatus> employees = userServiceClient.getEmployees(ids).data().employees();
```

- 받는 서비스는 같은 토큰으로 권한을 판단하므로, **요청한 사원이 볼 수 있는 데이터만** 돌아온다.
- 타임아웃, `@LoadBalanced` 빌더 설정 등 전체 예시는 approval-service `global/client/UserServiceClientConfig` 참고.

## 테스트 작성

MockMvc 테스트에서는 `spring-security-test` 의 `jwt()` 로 토큰을 흉내 낸다. JWKS 서버가 필요 없다.

```java
// testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

mvc.perform(get("/api/v1/xxx")
        .with(jwt().jwt(j -> j.subject("7").claim("type", "access").claim("role", "USER"))));
```

- `@CurrentUser`, `@CurrentEmployeeId` 는 `sub`, `role` 클레임을 읽으므로 테스트에서도 넣어 준다.
- `jwt()` 는 기본으로 `role` 이 아닌 `scope` 클레임에서 권한을 만든다.
  `hasRole("ADMIN")` 경로를 테스트하려면 `.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))` 을 함께 넘긴다.
- 실제 서명·만료 검증은 라이브러리 테스트(`NexaSecurityIntegrationTest`)에서 확인한다.

자주 쓰는 요청은 헬퍼로 묶어 둔다 (approval-service `support/ApprovalTestFixtures` 참고).

```java
public final class AuthFixtures {

    private AuthFixtures() {
    }

    public static RequestPostProcessor user(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    public static RequestPostProcessor admin(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access").claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}

// 사용
mvc.perform(post("/api/v1/attn/clock-in").with(user(7)))
        .andExpect(status().isOk());

mvc.perform(get("/api/v1/attn/admin/employees/7/daily/2026-10-30").with(user(7)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_009"));

mvc.perform(get("/api/v1/attn/admin/employees/7/daily/2026-10-30").with(admin(1)))
        .andExpect(status().isOk());
```

## 적용 대상이 아닌 서비스

| 서비스 | 이유 |
|---|---|
| gateway-service | WebFlux(리액티브)라 서블릿용 라이브러리를 쓸 수 없다. 쿠키 검증과 블랙리스트 검사를 직접 한다 |
| user-service | 토큰 발급자. 자체 `JwtFilter` 로 블랙리스트까지 검사하며, 로그인/재발급/로그아웃 흐름이 달라 별도 검토 필요 |

## 버전 올리기 (라이브러리 담당자)

1. `Backend/nexa-security/build.gradle` 의 `version` 을 올린다 (호환이 깨지면 메이저, 기능 추가는 마이너, 수정은 패치).
2. MR 머지 후 develop 에서 같은 번호로 태그를 push 한다.
   ```bash
   git tag -a nexa-security-v1.1.0 origin/develop -m "nexa-security 1.1.0"
   git push origin nexa-security-v1.1.0
   ```
3. `publish-nexa-security` job 이 테스트 후 레지스트리에 올린다. 태그와 `version` 이 다르면 실패한다.
4. 각 서비스는 `build.gradle` 의 버전을 직접 올려 적용한다 (올리기 전까지는 영향 없음).

- 이미 올라간 버전은 다시 올리지 않는다. 수정이 필요하면 새 버전을 낸다.
- 배포 전 로컬에서 서비스와 함께 확인하려면 `--include-build` 로 소스를 끼워 빌드한다.
  ```bash
  cd Backend/approval-service
  ./gradlew test --include-build ../nexa-security
  ```
