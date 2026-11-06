package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.client.EmployeeInfo;
import com.ssafy.attendanceservice.domain.attendance.client.UserClient;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.nexa.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 다른 사원의 근태를 볼 수 있는 범위 (팀 결정).
 * 팀장·본부장은 자기 부서와 그 하위 부서의 사원 전부(본인·동급 포함), 대표이사와 인사 관리자(role ADMIN)는 전사.
 * 그 외 직책은 볼 수 없다. 직책 이름은 user-service에서 받는다.
 * 하위 부서는 사원마다 소속 부서 → 최상위 부서 체인에 기준 부서가 있는지로 판단한다
 */
@Component
@RequiredArgsConstructor
public class AttendanceViewScope {

    private static final Set<String> DEPT_HEAD_DUTIES = Set.of("팀장", "본부장");
    private static final String CEO_DUTY = "대표이사";

    private final UserClient userClient;

    /**
     * 볼 수 있는 사원 목록. deptId를 주면 그 부서와 하위 부서만.
     * 볼 수 없는 직책이거나 범위 밖 부서를 요청하면 BusinessException(ACCESS_DENIED)
     */
    public List<EmployeeInfo> employees(AuthUser user, Long deptId) {
        boolean companyWide = isCompanyWide(user);
        // 전사 조회 권한자가 부서를 안 고르면 전사, 그 외는 고른 부서(없으면 자기 부서) 기준
        Long baseDeptId = deptId != null ? deptId : (companyWide ? null : user.deptId());
        List<EmployeeInfo> all = userClient.getEmployees(null);
        if (baseDeptId == null) {
            return all;
        }

        ChainCache chains = new ChainCache(all);
        // 부서장이 다른 부서를 고르면, 그 부서가 자기 부서 아래에 있어야 한다
        if (!companyWide && deptId != null && !deptId.equals(user.deptId()) && !chains.isUnder(deptId, user.deptId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "볼 수 없는 부서입니다.");
        }
        return all.stream()
                .filter(employee -> chains.chainOf(employee.deptId()).contains(baseDeptId))
                .toList();
    }

    /** 이 사원의 근태를 볼 수 있는가 (본인 포함) */
    public boolean canView(AuthUser user, Long empId) {
        if (user.employeeId().equals(empId) || user.isAdmin()) {
            return true;
        }
        String duty = dutyName(user);
        if (CEO_DUTY.equals(duty)) {
            return true;
        }
        return DEPT_HEAD_DUTIES.contains(duty) && user.deptId() != null
                && userClient.getOrgChainDeptIds(empId).contains(user.deptId());
    }

    /** 전사 조회 권한 (대표이사, 인사 관리자). 팀장급이 아니면 BusinessException(ACCESS_DENIED) */
    private boolean isCompanyWide(AuthUser user) {
        if (user.isAdmin()) {
            return true;
        }
        String duty = dutyName(user);
        if (CEO_DUTY.equals(duty)) {
            return true;
        }
        if (!DEPT_HEAD_DUTIES.contains(duty) || user.deptId() == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "팀장 이상 직책만 조회할 수 있습니다.");
        }
        return false;
    }

    /** 토큰의 직책 ID로 직책 이름. 직책 클레임이 없는 토큰(이전 발급분)이면 빈 문자열 (어떤 범위에도 해당하지 않음) */
    private String dutyName(AuthUser user) {
        return user.dutyId() == null ? "" : userClient.getDuty(user.dutyId()).name();
    }

    /** 부서별 소속 → 최상위 부서 체인. 한 부서당 그 부서 사원 한 명으로 한 번만 조회한다 */
    private class ChainCache {

        private final Map<Long, Long> anyEmpByDept = new HashMap<>();
        private final Map<Long, List<Long>> chainByDept = new HashMap<>();

        ChainCache(List<EmployeeInfo> employees) {
            employees.stream()
                    .filter(employee -> employee.deptId() != null)
                    .forEach(employee -> anyEmpByDept.putIfAbsent(employee.deptId(), employee.empId()));
        }

        List<Long> chainOf(Long deptId) {
            if (deptId == null) {
                return List.of();
            }
            return chainByDept.computeIfAbsent(deptId,
                    id -> userClient.getOrgChainDeptIds(anyEmpByDept.get(id)));
        }

        /** deptId 부서가 upperDeptId 부서 아래(또는 같은 부서)인가. 사원이 없는 부서는 알 수 없어 false */
        boolean isUnder(Long deptId, Long upperDeptId) {
            if (!anyEmpByDept.containsKey(deptId)) {
                // 사원이 없는 부서: 그 아래 부서 사원의 체인에서 찾는다
                return anyEmpByDept.keySet().stream()
                        .map(this::chainOf)
                        .anyMatch(chain -> chain.indexOf(deptId) >= 0
                                && chain.indexOf(upperDeptId) >= chain.indexOf(deptId));
            }
            return chainOf(deptId).contains(upperDeptId);
        }
    }
}
