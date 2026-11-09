package com.ssafy.approval_service.support;

import com.ssafy.approval_service.global.client.UserServiceClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 통합 테스트용 user-service. 따로 지정하지 않은 사원은 모두 재직(EMPLOYED)으로 응답하므로
 * 기존 테스트는 사원 ID 를 아무 숫자나 써도 된다. 상태는 컨텍스트 전체가 공유하므로
 * 테스트마다 겹치지 않는 사원 ID 를 쓰고, down 은 테스트가 끝나면 reset() 으로 되돌린다.
 */
public class FakeUserServiceClient implements UserServiceClient {

    private final Map<Long, String> statuses = new ConcurrentHashMap<>();
    private final Set<Long> missing = ConcurrentHashMap.newKeySet();
    private final List<List<Long>> calls = new CopyOnWriteArrayList<>();
    private final Map<Long, List<OrgUnit>> orgChains = new ConcurrentHashMap<>();
    private volatile boolean down;

    @Override
    public EmployeeListResult getEmployees(List<Long> ids) {
        calls.add(List.copyOf(ids));
        if (down) {
            throw new ResourceAccessException("user-service 장애 (테스트)");
        }
        List<EmployeeStatus> found = ids.stream()
                .filter(id -> !missing.contains(id))
                .map(id -> new EmployeeStatus(id, statuses.getOrDefault(id, "EMPLOYED")))
                .toList();
        return new EmployeeListResult(new EmployeeList(found));
    }

    /** 부서 체인. setOrgChain 으로 지정하지 않은 사원은 user-service 처럼 404 */
    @Override
    public OrgChainResult getOrgChain(Long employeeId) {
        if (down) {
            throw new ResourceAccessException("user-service 장애 (테스트)");
        }
        List<OrgUnit> chain = orgChains.get(employeeId);
        if (chain == null) {
            throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
        }
        return new OrgChainResult(new OrgChain(employeeId, chain.getFirst().deptId(), chain));
    }

    /** 사원의 부서 체인 지정. 소속 부서부터 최상위 순으로 */
    public void setOrgChain(long empId, OrgUnit... chain) {
        orgChains.put(empId, List.of(chain));
    }

    public void setStatus(long empId, String status) {
        statuses.put(empId, status);
    }

    public void markMissing(long empId) {
        missing.add(empId);
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    /** 이 사원 ID 가 들어간 호출만 (다른 테스트의 호출과 섞이지 않게) */
    public List<List<Long>> callsContaining(long empId) {
        return calls.stream().filter(call -> call.contains(empId)).toList();
    }

    public void reset() {
        down = false;
    }
}
