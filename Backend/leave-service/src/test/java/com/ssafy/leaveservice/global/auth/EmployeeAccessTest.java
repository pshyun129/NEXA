package com.ssafy.leaveservice.global.auth;

import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EmployeeAccessTest {
    private final EmployeeAccess access = new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));

    @AfterEach
    void clearToken() {
        TestTokens.clear();
    }

    @Test
    void tokenSubjectIsCurrentEmployee() {
        TestTokens.authenticate(7L, "USER");
        assertThat(access.currentEmployeeId()).isEqualTo(7L);
        assertThat(access.isAdmin(7L)).isFalse();
    }

    @Test
    void adminRoleAloneDoesNotGrantLeaveAdministratorAccess() {
        TestTokens.authenticate(1L, "ADMIN");
        assertThat(access.isAdmin(1L)).isFalse();
        assertThat(access.isAdmin(2L)).isFalse();
        assertThat(access.isAdmin(null)).isFalse();
    }

    @Test
    void withoutTokenNoEmployeeAndNotAdmin() {
        assertThat(access.currentEmployeeId()).isNull();
        assertThat(access.isAdmin(1L)).isFalse();
    }
}
