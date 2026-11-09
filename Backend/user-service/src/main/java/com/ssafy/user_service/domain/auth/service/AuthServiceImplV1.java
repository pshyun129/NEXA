package com.ssafy.user_service.domain.auth.service;

import com.ssafy.user_service.domain.auth.dto.LoginRequest;
import com.ssafy.user_service.domain.auth.dto.TokenResponse;
import com.ssafy.user_service.domain.user.dto.EmployeeSummary;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.service.EmployeeService;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.exception.GlobalExceptionHandler;
import com.ssafy.user_service.global.security.TokenStore;
import com.ssafy.user_service.global.security.jwt.IssuedToken;
import com.ssafy.user_service.global.security.jwt.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthServiceImplV1 implements AuthService {

    /** 잠금 해제 시각(lockedUntil) 표기 시간대. 서버 기본 시간대와 무관하게 +09:00 으로 내려준다 */
    private static final ZoneId LOCK_ZONE = ZoneId.of("Asia/Seoul");

    private final EmployeeService employeeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;
    private final LoginAttemptService loginAttemptService;

    // 존재하지 않는 계정에 더미 암호값을 두어 BCrypt 비교를 수행해 응답 시간으로 계정 존재가 드러나지 않게 함.
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    // 각 메서드는 단건 조회만 하므로 @Transactional을 두지 않는다 (BCrypt/Redis 동안 DB 커넥션을 잡지 않기 위해)
    @Override
    public TokenResponse login(LoginRequest request) {
        String loginId = request.email();
        // 잠겨 있으면 해제 시각과 남은 초를 알려 준다 (클라이언트가 고정 15분이 아니라 실제 남은 시간으로 센다)
        Optional<Duration> locked = loginAttemptService.lockRemaining(loginId);
        if (locked.isPresent()) {
            long seconds = locked.get().toSeconds();
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, Map.of(
                    "lockedUntil", OffsetDateTime.now(LOCK_ZONE).plusSeconds(seconds).toString(),
                    GlobalExceptionHandler.RETRY_AFTER_SECONDS, seconds));
        }

        Employee employee = employeeService.findByLoginId(loginId).orElse(null);
        String hash = employee != null && employee.getPasswordEnc() != null ? employee.getPasswordEnc() : dummyHash;
        boolean matched = passwordEncoder.matches(request.pass(), hash);

        if (employee == null || !matched) {
            // 이번 실패로 잠겼어도 응답은 AUTH_005(remainingAttempts=0). 잠금 시각은 다음 시도의 AUTH_010 이 알려 준다
            int remaining = loginAttemptService.recordFailure(loginId);
            throw new BusinessException(ErrorCode.LOGIN_FAILED, Map.of("remainingAttempts", remaining));
        }
        if (employee.isTerminated()) {
            throw new BusinessException(ErrorCode.ACCOUNT_TERMINATED);
        }
        loginAttemptService.reset(loginId);

        Issued issued = issue(employee);
        tokenStore.saveRefreshToken(employee.getEmpId(), issued.refresh().id(), issued.refresh().remainingMillis());
        return issued.response();
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        Long employeeId = blacklistIfValid(accessToken);
        if (employeeId == null && refreshToken != null) {
            try {
                Claims claims = jwtUtil.parseClaimsAllowExpired(refreshToken);
                if (JwtUtil.TYPE_REFRESH.equals(jwtUtil.getType(claims))) {
                    employeeId = jwtUtil.getUserId(claims);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
            }
        }
        if (employeeId != null) {
            tokenStore.deleteRefreshToken(employeeId);
        }
    }

    @Override
    public TokenResponse reissue(String refreshToken, String currentAccessToken) {
        Claims claims;
        try {
            claims = jwtUtil.parseClaims(refreshToken); // 서명/만료 검증
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        // accessToken을 refreshToken 자리에 넣는 것을 차단
        if (!JwtUtil.TYPE_REFRESH.equals(jwtUtil.getType(claims))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        Long employeeId = jwtUtil.getUserId(claims);
        Employee employee = employeeService.findById(employeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        // 로그인 이후 퇴직 처리된 계정은 재발급으로 세션을 이어갈 수 없다
        if (employee.isTerminated()) {
            tokenStore.deleteRefreshToken(employeeId);
            throw new BusinessException(ErrorCode.ACCOUNT_TERMINATED);
        }

        // DB 조회를 모두 마친 뒤 마지막에 Redis를 바꾼다 (실패 시 회전된 상태가 남지 않도록)
        Issued issued = issue(employee);
        boolean rotated = tokenStore.rotateRefreshToken(
                employeeId, jwtUtil.getTokenId(claims), issued.refresh().id(), issued.refresh().remainingMillis());
        if (!rotated) {
            // 저장값이 없거나(로그아웃됨) 다른 jti(이미 회전된 토큰 재사용 → 세션 삭제됨)
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 교체된 이전 access 토큰이 남은 수명 동안 쓰이지 않도록 차단
        blacklistIfValid(currentAccessToken);
        return issued.response();
    }

    /** access 토큰이 서명 검증을 통과하면 주체를 반환하고, 아직 유효하면 jti를 블랙리스트에 넣는다 */
    private Long blacklistIfValid(String accessToken) {
        if (accessToken == null) return null;
        try {
            Claims claims = jwtUtil.parseClaimsAllowExpired(accessToken);
            if (!JwtUtil.TYPE_ACCESS.equals(jwtUtil.getType(claims))) return null;
            long remain = jwtUtil.getRemainExpiration(claims);
            if (remain > 0) {
                tokenStore.blacklistAccessToken(jwtUtil.getTokenId(claims), remain);
            }
            return jwtUtil.getUserId(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return null; // 위변조된 토큰은 무시
        }
    }

    /** 발급 결과. refresh의 jti/만료는 Redis 저장에 필요해 함께 돌려준다 */
    private record Issued(TokenResponse response, IssuedToken refresh) {
    }

    // 토큰 생성 + 부서/직급명 조회. Redis에는 손대지 않는다 (reissue가 DB 조회를 끝낸 뒤 마지막에 회전하는 전제)
    private Issued issue(Employee employee) {
        EmployeeSummary summary = employeeService.summarize(employee);
        JobAssignment job = employee.getJob();
        IssuedToken access = job == null
                ? jwtUtil.generateAccessToken(employee.getEmpId(), employee.getRole(), null, null, null)
                : jwtUtil.generateAccessToken(employee.getEmpId(), employee.getRole(),
                        job.getDeptId(), job.getGradeId(), job.getDutyId());
        IssuedToken refresh = jwtUtil.generateRefreshToken(employee.getEmpId());
        return new Issued(new TokenResponse(access.value(), refresh.value(),
                summary.employeeId(), summary.deptName(), summary.gradeName()), refresh);
    }
}
