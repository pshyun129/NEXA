package com.ssafy.leaveservice.global.auth;

import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.HrIntegrationProperties;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveAdministratorAccessTest {
    private final HrClient hr = mock(HrClient.class);

    @AfterEach
    void clearToken() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "USER,휴가관리자,true", "ADMIN,휴가관리자,true", "ADMIN,휴가 관리자,false",
            "ADMIN,팀장,false", "ADMIN,본부장,false", "ADMIN,대표이사,false"
    })
    void exactLatestDutyDeterminesPermission(
            String role,
            String dutyName,
            boolean permitted
    ) {
        authenticate(role);
        when(hr.isAvailable()).thenReturn(true);
        when(hr.findEmployee(10L)).thenReturn(new HrClient.EmployeeInfo(
                10L, 3L, 7L, null, null, HrClient.EmploymentStatus.EMPLOYED));
        when(hr.findDuty(7L)).thenReturn(new HrClient.DutyInfo(7L, dutyName));

        assertThat(access("dutyId").isAdministrator(10L)).isEqualTo(permitted);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sub", "role", "type"})
    void reservedClaimCannotBeUsedAsDutyProof(String key) {
        authenticate("ADMIN");
        when(hr.isAvailable()).thenReturn(true);

        assertThatThrownBy(() -> access(key).isAdministrator(10L)).isInstanceOf(BusinessException.class);
        verify(hr, never()).findEmployee(any());
    }

    @ParameterizedTest
    @EnumSource(value = HrClient.EmploymentStatus.class, names = {"ON_LEAVE", "TERMINATED"})
    void inactiveEmployeeCannotUseAdministratorDuty(HrClient.EmploymentStatus employmentStatus) {
        authenticate("ADMIN");
        when(hr.isAvailable()).thenReturn(true);
        when(hr.findEmployee(10L)).thenReturn(new HrClient.EmployeeInfo(
                10L, 3L, 7L, null, null, employmentStatus));

        assertThat(access("dutyId").isAdministrator(10L)).isFalse();
        verify(hr, never()).findDuty(any());
    }

    @Test
    void staleJwtDutyCannotUseLatestAdministratorDuty() {
        authenticate("ADMIN");
        when(hr.isAvailable()).thenReturn(true);
        when(hr.findEmployee(10L)).thenReturn(new HrClient.EmployeeInfo(
                10L, 3L, 8L, null, null, HrClient.EmploymentStatus.EMPLOYED));

        assertThat(access("dutyId").isAdministrator(10L)).isFalse();
        verify(hr, never()).findDuty(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableHrFailsClosedWithoutGrantingPermission(boolean remoteFailure) {
        authenticate("ADMIN");
        when(hr.isAvailable()).thenReturn(remoteFailure);
        if (remoteFailure) {
            when(hr.findEmployee(10L)).thenThrow(new IllegalStateException("HR unavailable"));
        }

        assertThatThrownBy(() -> access("dutyId").isAdministrator(10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.HR_VERIFICATION_UNAVAILABLE));
        verify(hr, never()).findDuty(any());
    }

    private LeaveAdministratorAccess access(String dutyClaim) {
        HrIntegrationProperties settings = new HrIntegrationProperties(
                true,
                "http://user.test",
                "user-service",
                "employee",
                "members",
                "duty",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                new HrIntegrationProperties.JwtClaims("deptId", dutyClaim)
        );
        return new LeaveAdministratorAccess(hr, settings);
    }

    private void authenticate(String role) {
        Jwt jwt = Jwt.withTokenValue("admin-test")
                .header("alg", "RS256")
                .subject("10")
                .claim("role", role)
                .claim("dutyId", 7L)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
