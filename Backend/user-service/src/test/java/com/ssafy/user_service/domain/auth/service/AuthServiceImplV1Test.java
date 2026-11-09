package com.ssafy.user_service.domain.auth.service;

import com.ssafy.user_service.domain.auth.dto.LoginRequest;
import com.ssafy.user_service.domain.auth.dto.TokenResponse;
import com.ssafy.user_service.domain.user.dto.EmployeeSummary;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.service.EmployeeService;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.Role;
import com.ssafy.user_service.global.security.TokenStore;
import com.ssafy.user_service.global.security.jwt.IssuedToken;
import com.ssafy.user_service.global.security.jwt.JwtKeyProvider;
import com.ssafy.user_service.global.security.jwt.JwtProperties;
import com.ssafy.user_service.global.security.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceImplV1Test {

    private final EmployeeService employeeService = mock(EmployeeService.class);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private JwtUtil jwtUtil;
    private AuthServiceImplV1 service;
    private Employee employee;

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties props = new JwtProperties("", "", 900_000L, 604_800_000L);
        JwtKeyProvider keys = new JwtKeyProvider(props);
        keys.init();
        jwtUtil = new JwtUtil(keys, props);
        service = new AuthServiceImplV1(employeeService, encoder, jwtUtil, tokenStore, loginAttemptService);

        employee = Employee.builder().loginId("a@b.c").passwordEnc(encoder.encode("secret12")).name("A").role(Role.ADMIN).build();
        ReflectionTestUtils.setField(employee, "empId", 10L);
        when(employeeService.summarize(employee)).thenReturn(new EmployeeSummary(10L, "A", "인사팀", "과장"));
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    void 잠긴_계정은_조회_없이_거부() {
        when(loginAttemptService.lockRemaining("a@b.c")).thenReturn(Optional.of(Duration.ofSeconds(90)));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.c", "x")))
                .isInstanceOf(BusinessException.class).matches(t -> codeOf(t) == ErrorCode.ACCOUNT_LOCKED)
                .matches(t -> ((BusinessException) t).getDetails().get("retryAfterSeconds").equals(90L))
                .matches(t -> ((BusinessException) t).getDetails().get("lockedUntil") instanceof String);
        verifyNoInteractions(employeeService);
    }

    @Test
    void 없는_계정과_틀린_비밀번호는_같은_오류이며_실패를_기록() {
        when(employeeService.findByLoginId("none@b.c")).thenReturn(Optional.empty());
        when(employeeService.findByLoginId("a@b.c")).thenReturn(Optional.of(employee));
        when(loginAttemptService.recordFailure("none@b.c")).thenReturn(3);

        assertThatThrownBy(() -> service.login(new LoginRequest("none@b.c", "x")))
                .matches(t -> codeOf(t) == ErrorCode.LOGIN_FAILED)
                .matches(t -> ((BusinessException) t).getDetails().get("remainingAttempts").equals(3));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.c", "wrong")))
                .matches(t -> codeOf(t) == ErrorCode.LOGIN_FAILED);
        verify(loginAttemptService).recordFailure("none@b.c");
        verify(loginAttemptService).recordFailure("a@b.c");
        verify(tokenStore, never()).saveRefreshToken(anyLong(), anyString(), anyLong());
    }

    @Test
    void 퇴직자는_로그인_불가() {
        employee.terminate(LocalDate.now(), "퇴사");
        when(employeeService.findByLoginId("a@b.c")).thenReturn(Optional.of(employee));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.c", "secret12")))
                .matches(t -> codeOf(t) == ErrorCode.ACCOUNT_TERMINATED);
    }

    @Test
    void 로그인_성공시_refresh_jti를_저장하고_실패횟수를_초기화() {
        when(employeeService.findByLoginId("a@b.c")).thenReturn(Optional.of(employee));
        TokenResponse res = service.login(new LoginRequest("a@b.c", "secret12"));

        var refreshClaims = jwtUtil.parseClaims(res.refreshToken());
        ArgumentCaptor<String> jti = ArgumentCaptor.forClass(String.class);
        verify(tokenStore).saveRefreshToken(eq(10L), jti.capture(), longThat(ttl -> ttl > 0));
        assertThat(jti.getValue()).isEqualTo(refreshClaims.getId());
        assertThat(jwtUtil.getRole(jwtUtil.parseClaims(res.accessToken()))).isEqualTo(Role.ADMIN);
        assertThat(res.deptName()).isEqualTo("인사팀");
        verify(loginAttemptService).reset("a@b.c");
    }

    @Test
    void 로그인시_access_토큰에_부서_직급_직책_ID를_싣는다() {
        ReflectionTestUtils.setField(employee, "job",
                JobAssignment.builder().deptId(12L).gradeId(3L).dutyId(5L).build());
        when(employeeService.findByLoginId("a@b.c")).thenReturn(Optional.of(employee));

        TokenResponse res = service.login(new LoginRequest("a@b.c", "secret12"));

        var claims = jwtUtil.parseClaims(res.accessToken());
        assertThat(jwtUtil.getDeptId(claims)).isEqualTo(12L);
        assertThat(jwtUtil.getGradeId(claims)).isEqualTo(3L);
        assertThat(jwtUtil.getDutyId(claims)).isEqualTo(5L);
        assertThat(jwtUtil.getDeptId(jwtUtil.parseClaims(res.refreshToken()))).isNull();
    }

    @Test
    void access_토큰을_refresh_자리에_넣으면_거부() {
        String access = jwtUtil.generateAccessToken(10L, Role.USER, null, null, null).value();
        assertThatThrownBy(() -> service.reissue(access, null))
                .matches(t -> codeOf(t) == ErrorCode.REFRESH_TOKEN_INVALID);
        verifyNoInteractions(tokenStore);
    }

    @Test
    void 재발급은_원자적_회전에_성공해야_하며_이전_access를_차단() {
        when(employeeService.findById(10L)).thenReturn(Optional.of(employee));
        IssuedToken oldRefresh = jwtUtil.generateRefreshToken(10L);
        IssuedToken oldAccess = jwtUtil.generateAccessToken(10L, Role.ADMIN, null, null, null);
        when(tokenStore.rotateRefreshToken(eq(10L), eq(oldRefresh.id()), anyString(), anyLong())).thenReturn(true);

        TokenResponse res = service.reissue(oldRefresh.value(), oldAccess.value());

        assertThat(jwtUtil.parseClaims(res.refreshToken()).getId()).isNotEqualTo(oldRefresh.id());
        verify(tokenStore).blacklistAccessToken(eq(oldAccess.id()), longThat(ttl -> ttl > 0));
    }

    @Test
    void 회전_실패면_거부() {
        when(employeeService.findById(10L)).thenReturn(Optional.of(employee));
        IssuedToken refresh = jwtUtil.generateRefreshToken(10L);
        when(tokenStore.rotateRefreshToken(anyLong(), anyString(), anyString(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.reissue(refresh.value(), null))
                .matches(t -> codeOf(t) == ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 퇴직자_재발급은_세션을_지우고_거부() {
        employee.terminate(LocalDate.now(), null);
        when(employeeService.findById(10L)).thenReturn(Optional.of(employee));
        IssuedToken refresh = jwtUtil.generateRefreshToken(10L);
        assertThatThrownBy(() -> service.reissue(refresh.value(), null))
                .matches(t -> codeOf(t) == ErrorCode.ACCOUNT_TERMINATED);
        verify(tokenStore).deleteRefreshToken(10L);
        verify(tokenStore, never()).rotateRefreshToken(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void 로그아웃은_만료된_access여도_refresh로_주체를_찾아_세션을_지운다() throws Exception {
        JwtProperties expiredProps = new JwtProperties("", "", -1_000L, 604_800_000L);
        JwtKeyProvider sameKeys = (JwtKeyProvider) ReflectionTestUtils.getField(jwtUtil, "keyProvider");
        JwtUtil expiredIssuer = new JwtUtil(sameKeys, expiredProps);
        String expiredAccess = expiredIssuer.generateAccessToken(10L, Role.USER, null, null, null).value();
        String refresh = jwtUtil.generateRefreshToken(10L).value();

        service.logout(expiredAccess, refresh);

        verify(tokenStore, never()).blacklistAccessToken(anyString(), anyLong());
        verify(tokenStore).deleteRefreshToken(10L);
    }
}
