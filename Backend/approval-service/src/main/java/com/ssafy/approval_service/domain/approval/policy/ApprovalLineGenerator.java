package com.ssafy.approval_service.domain.approval.policy;

import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLineRequest;
import com.ssafy.approval_service.global.client.UserServiceClient;
import com.ssafy.approval_service.global.client.UserServiceClient.OrgUnit;
import com.ssafy.approval_service.global.exception.BusinessException;
import com.ssafy.approval_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 조직도(user-service 부서 체인)로 결재선을 만든다.
 *
 * 기안자 소속 부서부터 위로 올라가며 각 단계의 부서장을 결재자로 모으고, 전결 단계 이상인 부서의 부서장을 넣으면 멈춘다.
 * - 공석인 부서는 건너뛴다 → 전결권자 자리가 비면 그 위 단계 부서장이 결재한다
 * - 기안자 본인은 넣지 않는다 → 팀장이 기안하면 한 단계 위(본부장)부터
 * - 같은 사람은 한 번만 (겸직)
 * - 결재자가 한 명도 없고 기안자가 최상위 부서장이면 자가결재 (대표이사 기안)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalLineGenerator {

    private final UserServiceClient userServiceClient;

    public GeneratedApprovalLine generate(Long drafterId, ApprovalLevel requiredLevel) {
        List<OrgUnit> chain = fetchChain(drafterId);

        Set<Long> approvers = new LinkedHashSet<>();
        boolean reachedRequiredLevel = false;
        for (OrgUnit unit : chain) {
            Long head = unit.headEmpId();
            // 공석이거나 기안자 본인이면 이 단계는 결재할 사람이 없으므로 위로 올라간다
            if (head == null || head.equals(drafterId)) {
                continue;
            }
            // 겸직: 이미 결재선에 있으면 다시 넣지 않지만, 이 단계의 결재권자로는 친다
            approvers.add(head);
            if (ApprovalLevel.ofDeptType(unit.deptType()).isAtLeast(requiredLevel)) {
                reachedRequiredLevel = true;
                break;
            }
        }

        if (approvers.isEmpty() && isTopHead(chain, drafterId)) {
            return new GeneratedApprovalLine(List.of(), true);
        }
        if (!reachedRequiredLevel) {
            // 위로 끝까지 올라가도 전결 단계 이상의 부서장이 없다 (최상위 부서장 공석 등). 조직 데이터를 고쳐야 한다
            // 화면에는 기본 문구만 보내고 내부 값(전결 단계 코드·사번)은 로그로만 남긴다 (QA C22)
            log.warn("결재선 생성 실패: 전결 단계 {} 이상의 부서장이 없음. drafterId={}", requiredLevel, drafterId);
            throw new BusinessException(ErrorCode.APPROVER_NOT_RESOLVED);
        }

        List<ApprovalLineRequest> lines = new ArrayList<>();
        short step = 1;
        for (Long approver : approvers) {
            lines.add(new ApprovalLineRequest(approver, step++, LineRole.APPROVER));
        }
        return new GeneratedApprovalLine(lines, false);
    }

    /** 기안자가 체인 맨 위(최상위 부서)의 부서장인지 */
    private static boolean isTopHead(List<OrgUnit> chain, Long drafterId) {
        return !chain.isEmpty() && drafterId.equals(chain.getLast().headEmpId());
    }

    private List<OrgUnit> fetchChain(Long drafterId) {
        try {
            UserServiceClient.OrgChainResult result = userServiceClient.getOrgChain(drafterId);
            if (result == null || result.data() == null || result.data().chain() == null) {
                throw new IllegalStateException("user-service 응답에 부서 체인이 없음");
            }
            return result.data().chain();
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "존재하지 않는 기안자: " + drafterId);
        } catch (RestClientException | IllegalStateException e) {
            // 연결 실패, 타임아웃, 5xx, Eureka 에 인스턴스 없음 등
            log.warn("user-service 부서 체인 조회 실패 (drafter={}): {}", drafterId, e.getMessage());
            throw new BusinessException(ErrorCode.EMPLOYEE_SERVICE_UNAVAILABLE);
        }
    }
}
