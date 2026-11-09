package com.ssafy.approval_service.domain.approval.policy;

import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLineRequest;
import com.ssafy.approval_service.global.client.UserServiceClient.OrgUnit;
import com.ssafy.approval_service.global.exception.BusinessException;
import com.ssafy.approval_service.global.exception.ErrorCode;
import com.ssafy.approval_service.support.FakeUserServiceClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조직도로 결재선 만들기. 기본 조직:
 * 회사(대표 900) > 본부(본부장 800) > 팀(팀장 700) > 팀원 1
 */
class ApprovalLineGeneratorTest {

    private static final long MEMBER = 1L;
    private static final long TEAM_HEAD = 700L;
    private static final long DIVISION_HEAD = 800L;
    private static final long CEO = 900L;

    private final FakeUserServiceClient userService = new FakeUserServiceClient();
    private final ApprovalLineGenerator generator = new ApprovalLineGenerator(userService);

    private static OrgUnit team(Long head) {
        return new OrgUnit(30L, "팀", "TEAM", head);
    }

    private static OrgUnit division(Long head) {
        return new OrgUnit(20L, "본부", "DIVISION", head);
    }

    private static OrgUnit company(Long head) {
        return new OrgUnit(10L, "회사", "COMPANY", head);
    }

    private List<Long> approvers(long drafter, ApprovalLevel level) {
        GeneratedApprovalLine line = generator.generate(drafter, level);
        assertThat(line.selfApproval()).isFalse();
        assertThat(line.lines()).allMatch(l -> l.role() == LineRole.APPROVER);
        // step 은 1부터 차례로
        assertThat(line.lines()).extracting(l -> (int) l.step())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, line.lines().size()).boxed().toList());
        return line.lines().stream().map(ApprovalLineRequest::empId).toList();
    }

    @Test
    void 팀원은_전결_단계까지_아래에서_위로_부서장이_결재한다() {
        userService.setOrgChain(MEMBER, team(TEAM_HEAD), division(DIVISION_HEAD), company(CEO));

        assertThat(approvers(MEMBER, ApprovalLevel.TEAM)).containsExactly(TEAM_HEAD);
        assertThat(approvers(MEMBER, ApprovalLevel.DIVISION)).containsExactly(TEAM_HEAD, DIVISION_HEAD);
        assertThat(approvers(MEMBER, ApprovalLevel.COMPANY)).containsExactly(TEAM_HEAD, DIVISION_HEAD, CEO);
    }

    @Test
    void 팀장이_기안하면_본인을_빼고_한_단계_위부터() {
        userService.setOrgChain(TEAM_HEAD, team(TEAM_HEAD), division(DIVISION_HEAD), company(CEO));

        // 팀장 전결 문서(팀장 휴가 등)는 본부장 전결
        assertThat(approvers(TEAM_HEAD, ApprovalLevel.TEAM)).containsExactly(DIVISION_HEAD);
        assertThat(approvers(TEAM_HEAD, ApprovalLevel.DIVISION)).containsExactly(DIVISION_HEAD);
    }

    @Test
    void 본부장이_기안하면_대표이사가_결재한다() {
        userService.setOrgChain(DIVISION_HEAD, division(DIVISION_HEAD), company(CEO));

        assertThat(approvers(DIVISION_HEAD, ApprovalLevel.TEAM)).containsExactly(CEO);
        assertThat(approvers(DIVISION_HEAD, ApprovalLevel.DIVISION)).containsExactly(CEO);
    }

    @Test
    void 대표이사가_기안하면_자가결재() {
        userService.setOrgChain(CEO, company(CEO));

        for (ApprovalLevel level : ApprovalLevel.values()) {
            GeneratedApprovalLine line = generator.generate(CEO, level);
            assertThat(line.selfApproval()).isTrue();
            assertThat(line.lines()).isEmpty();
        }
    }

    @Test
    void 팀장이_공석이면_본부장이_결재한다() {
        userService.setOrgChain(MEMBER, team(null), division(DIVISION_HEAD), company(CEO));

        assertThat(approvers(MEMBER, ApprovalLevel.TEAM)).containsExactly(DIVISION_HEAD);
    }

    @Test
    void 본부장이_공석이면_대표이사가_결재한다() {
        userService.setOrgChain(MEMBER, team(TEAM_HEAD), division(null), company(CEO));

        assertThat(approvers(MEMBER, ApprovalLevel.TEAM)).containsExactly(TEAM_HEAD);
        assertThat(approvers(MEMBER, ApprovalLevel.DIVISION)).containsExactly(TEAM_HEAD, CEO);
    }

    @Test
    void 겸직이면_한_번만_넣는다() {
        // 팀장이 본부장을 겸직
        userService.setOrgChain(MEMBER, team(DIVISION_HEAD), division(DIVISION_HEAD), company(CEO));

        assertThat(approvers(MEMBER, ApprovalLevel.TEAM)).containsExactly(DIVISION_HEAD);
        assertThat(approvers(MEMBER, ApprovalLevel.DIVISION)).containsExactly(DIVISION_HEAD);
    }

    @Test
    void 본부_없이_회사_직속_팀이면_대표이사까지() {
        userService.setOrgChain(MEMBER, team(TEAM_HEAD), company(CEO));

        assertThat(approvers(MEMBER, ApprovalLevel.TEAM)).containsExactly(TEAM_HEAD);
        assertThat(approvers(MEMBER, ApprovalLevel.DIVISION)).containsExactly(TEAM_HEAD, CEO);
    }

    @Test
    void 부서_유형이_없거나_모르는_값이면_팀으로_본다() {
        userService.setOrgChain(MEMBER, new OrgUnit(30L, "팀", null, TEAM_HEAD),
                new OrgUnit(20L, "본부", "SECTION", DIVISION_HEAD), company(CEO));

        assertThat(approvers(MEMBER, ApprovalLevel.TEAM)).containsExactly(TEAM_HEAD);
        // 알 수 없는 유형은 팀이라 본부장 단계로 치지 않고 위로 올라간다
        assertThat(approvers(MEMBER, ApprovalLevel.DIVISION)).containsExactly(TEAM_HEAD, DIVISION_HEAD, CEO);
    }

    @Test
    void 전결_단계_이상의_부서장이_없으면_409() {
        userService.setOrgChain(MEMBER, team(TEAM_HEAD), division(null), company(null));

        assertThatThrownBy(() -> generator.generate(MEMBER, ApprovalLevel.DIVISION))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.APPROVER_NOT_RESOLVED);
    }

    @Test
    void 부서장이_모두_공석이면_409() {
        userService.setOrgChain(MEMBER, team(null), division(null), company(null));

        assertThatThrownBy(() -> generator.generate(MEMBER, ApprovalLevel.TEAM))
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.APPROVER_NOT_RESOLVED);
    }

    @Test
    void 기안자가_user_service에_없으면_400_사원_없음() {
        assertThatThrownBy(() -> generator.generate(12345L, ApprovalLevel.TEAM))
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.EMPLOYEE_NOT_FOUND);
    }

    @Test
    void user_service_장애면_503() {
        userService.setOrgChain(MEMBER, team(TEAM_HEAD));
        userService.setDown(true);

        assertThatThrownBy(() -> generator.generate(MEMBER, ApprovalLevel.TEAM))
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.EMPLOYEE_SERVICE_UNAVAILABLE);
    }
}
