package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.client.EmployeeInfo;
import com.ssafy.attendanceservice.domain.attendance.client.UserClient;
import com.ssafy.attendanceservice.domain.attendance.client.UserClient.DutyInfo;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.nexa.security.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 근태 조회 범위: 팀장·본부장은 자기 부서와 하위 부서, 대표이사는 전사, 그 외는 불가 (user-service는 목으로).
 * 조직: 회사(13) > 경영지원본부(10) > 인사팀(1), 회사(13) > 기술본부(11) > 개발팀(2)
 */
class AttendanceViewScopeTest {

    private static final long MEMBER = 1L, TEAM_LEAD = 2L, DIVISION_HEAD = 3L, CEO = 4L;

    private final UserClient userClient = mock(UserClient.class);
    private final AttendanceViewScope scope = new AttendanceViewScope(userClient);

    private static EmployeeInfo employee(long empId, long deptId, long dutyId) {
        return new EmployeeInfo(empId, "사원" + empId, deptId, "부서" + deptId, 1L, dutyId, "EMPLOYED");
    }

    private static AuthUser user(long empId, long deptId, long dutyId) {
        return new AuthUser(empId, "USER", deptId, 1L, dutyId);
    }

    @BeforeEach
    void setUp() {
        given(userClient.getEmployees(null)).willReturn(List.of(
                employee(101, 1, MEMBER),           // 인사팀 팀원
                employee(102, 1, TEAM_LEAD),        // 인사팀 팀장
                employee(103, 2, MEMBER),           // 개발팀 팀원
                employee(107, 10, DIVISION_HEAD),   // 경영지원본부 본부장
                employee(108, 11, DIVISION_HEAD),   // 기술본부 본부장
                employee(109, 13, CEO)));           // 대표이사
        given(userClient.getOrgChainDeptIds(101L)).willReturn(List.of(1L, 10L, 13L));
        given(userClient.getOrgChainDeptIds(102L)).willReturn(List.of(1L, 10L, 13L));
        given(userClient.getOrgChainDeptIds(103L)).willReturn(List.of(2L, 11L, 13L));
        given(userClient.getOrgChainDeptIds(107L)).willReturn(List.of(10L, 13L));
        given(userClient.getOrgChainDeptIds(108L)).willReturn(List.of(11L, 13L));
        given(userClient.getOrgChainDeptIds(109L)).willReturn(List.of(13L));
        given(userClient.getDuty(MEMBER)).willReturn(new DutyInfo(MEMBER, "팀원"));
        given(userClient.getDuty(TEAM_LEAD)).willReturn(new DutyInfo(TEAM_LEAD, "팀장"));
        given(userClient.getDuty(DIVISION_HEAD)).willReturn(new DutyInfo(DIVISION_HEAD, "본부장"));
        given(userClient.getDuty(CEO)).willReturn(new DutyInfo(CEO, "대표이사"));
    }

    private static List<Long> empIds(List<EmployeeInfo> employees) {
        return employees.stream().map(EmployeeInfo::empId).toList();
    }

    @Test
    void 팀장은_자기_부서원_전부() {
        assertThat(empIds(scope.employees(user(102, 1, TEAM_LEAD), null))).containsExactly(101L, 102L);
    }

    @Test
    void 본부장은_하위_팀까지() {
        assertThat(empIds(scope.employees(user(107, 10, DIVISION_HEAD), null))).containsExactly(101L, 102L, 107L);
    }

    @Test
    void 본부장이_하위_팀을_고르면_그_팀만() {
        assertThat(empIds(scope.employees(user(107, 10, DIVISION_HEAD), 1L))).containsExactly(101L, 102L);
    }

    @Test
    void 본부장이_다른_본부_팀을_고르면_403() {
        assertThatThrownBy(() -> scope.employees(user(107, 10, DIVISION_HEAD), 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void 대표이사는_전사() {
        assertThat(scope.employees(user(109, 13, CEO), null)).hasSize(6);
    }

    @Test
    void 대표이사가_부서를_고르면_그_부서와_하위만() {
        assertThat(empIds(scope.employees(user(109, 13, CEO), 11L))).containsExactly(103L, 108L);
    }

    @Test
    void 팀원은_볼_수_없음() {
        assertThatThrownBy(() -> scope.employees(user(101, 1, MEMBER), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void 직책_클레임이_없는_토큰은_볼_수_없음() {
        AuthUser oldToken = new AuthUser(102L, "USER");

        assertThatThrownBy(() -> scope.employees(oldToken, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 본인은_언제나_볼_수_있음() {
        assertThat(scope.canView(user(101, 1, MEMBER), 101L)).isTrue();
    }

    @Test
    void 본부장은_하위_팀_사원을_볼_수_있고_다른_본부_사원은_못_봄() {
        AuthUser head = user(107, 10, DIVISION_HEAD);

        assertThat(scope.canView(head, 101L)).isTrue();
        assertThat(scope.canView(head, 103L)).isFalse();
    }

    @Test
    void 팀원은_다른_사원을_못_봄() {
        assertThat(scope.canView(user(101, 1, MEMBER), 102L)).isFalse();
    }
}
