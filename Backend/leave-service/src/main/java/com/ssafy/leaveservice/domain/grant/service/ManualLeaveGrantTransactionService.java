package com.ssafy.leaveservice.domain.grant.service;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.domain.account.service.LeaveBalanceAmounts;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest.GrantMode;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.entity.LeaveGrant;
import com.ssafy.leaveservice.domain.grant.repository.LeaveGrantRepository;
import com.ssafy.leaveservice.domain.grant.repository.ManualLeaveGrantRepository;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveBalanceTypeRepository;
import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveSubmissionLockRepository;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ManualLeaveGrantTransactionService {
    private final LeaveSubmissionLockRepository locks;
    private final ManualLeaveGrantRepository receipts;
    private final EmployeeLeavePolicyAssignmentRepository assignments;
    private final LeaveBalanceTypeRepository balanceTypes;
    private final LeaveAccountRepository accounts;
    private final LeaveGrantRepository grants;
    private final LeaveLedgerRepository ledger;

    // 외부에 노출하지 않고 인증·HR 검증이 완료된 같은 패키지의 진입점에서 호출한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    ManualLeaveGrantResponse grant(Long employeeId, Long actorId, String key, ManualLeaveGrantRequest request) {
        // 기존 T1·ADJUST와 동일한 직원 잠금 후 관리자별 요청 키 잠금
        byte[] keyDigest = digest("MANUAL_GRANT:" + actorId + ":" + key);
        String ledgerKey = "leave:manual-grant:" + HexFormat.of().formatHex(keyDigest);
        String payloadHash = payloadHash(employeeId, request);
        locks.lockEmployee(employeeId);
        receipts.lockRequestKey(keyDigest);
        Optional<ManualLeaveGrantRepository.Receipt> existing = receipts.findReceipt(ledgerKey);
        if (existing.isPresent()) {
            if (!existing.get().payloadHash().equals(payloadHash)) {
                throw new BusinessException(ErrorCode.LEAVE_GRANT_KEY_CONFLICT);
            }
            return existing.get().response();
        }

        // 현재 배정과 활성 정책을 잠근 뒤 최신 시각으로 다시 확인
        MyLeavePolicyResponse policy = findPolicy(employeeId, locks.currentTime());
        receipts.lockPolicy(policy.policyId(), policy.policyVersionId());
        if (!balanceTypes.existsById(request.balanceTypeId())) {
            throw new BusinessException(ErrorCode.BALANCE_TYPE_NOT_FOUND);
        }
        if (!receipts.lockActiveBalanceType(request.balanceTypeId())) {
            throw new BusinessException(ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
        }
        OffsetDateTime asOf = locks.currentTime();
        MyLeavePolicyResponse latestPolicy = findPolicy(employeeId, asOf);
        if (!policy.policyId().equals(latestPolicy.policyId())
                || !policy.policyVersionId().equals(latestPolicy.policyVersionId())) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
        }
        policy = latestPolicy;
        if (request.expiresOn() != null && !request.expiresOn().isAfter(asOf)) {
            throw new BusinessException(ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
        }

        // 최초 지급과 명시적 추가 지급을 구분하며 과거 지급 이력도 보존
        boolean hasHistory = receipts.hasPaymentHistory(employeeId, request.balanceTypeId());
        if ((request.grantMode() == GrantMode.INITIAL && hasHistory)
                || (request.grantMode() == GrantMode.ADDITIONAL && !hasHistory)) {
            throw new BusinessException(ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
        }

        // 계정을 재사용하거나 생성하고 MANUAL 발생분과 최초 GRANT를 함께 저장
        LeaveBalanceType balanceType = balanceTypes.findById(request.balanceTypeId()).orElseThrow(
                () -> new BusinessException(ErrorCode.BALANCE_TYPE_NOT_FOUND)
        );
        LeaveAccount account = accounts.findByEmployeeIdAndBalanceTypeBalanceTypeId(employeeId, request.balanceTypeId())
                .orElseGet(() -> accounts.saveAndFlush(LeaveAccount.open(employeeId, balanceType)));
        LeaveGrant grant = grants.saveAndFlush(LeaveGrant.manual(
                account, policy.policyVersionId(), request.grantMinutes(), request.validFrom(), request.expiresOn(), asOf
        ));
        LeaveLedger entry = ledger.saveAndFlush(LeaveLedger.grant(
                account, grant.getGrantId(), request.grantMinutes(), actorId, request.reason(), ledgerKey, asOf
        ));

        // 기존 잔액 SQL과 발생분별 정합성·오버플로 검증으로 실제 가용량 계산
        long remainingMinutes = 0;
        List<LeaveAccountBalance> balances = accounts.findBalancesByEmployeeId(employeeId, asOf);
        for (LeaveAccountBalance balance : balances) {
            if (balance.getInvalidGrant()) {
                throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
            }
            LeaveBalanceAmounts amounts = LeaveBalanceAmounts.from(balance.getQuantityMinutes(), balance.getReservedMinutes());
            if (account.getAccountId().equals(balance.getAccountId())) {
                remainingMinutes = amounts.remainingMinutes();
            }
        }

        // 지급 당시 결과를 보존해 재요청 시 현재 잔액으로 응답을 바꾸지 않음
        ManualLeaveGrantResponse response = new ManualLeaveGrantResponse(
                account.getAccountId(), grant.getGrantId(), entry.getEntryId(), employeeId, request.balanceTypeId(),
                policy.policyVersionId(), request.grantMode(), request.grantMinutes(), remainingMinutes, asOf
        );
        receipts.saveReceipt(ledgerKey, payloadHash, actorId, response);
        return response;
    }

    private MyLeavePolicyResponse findPolicy(Long employeeId, OffsetDateTime asOf) {
        List<MyLeavePolicyResponse> policies = assignments.findApplicablePolicies(employeeId, asOf);
        if (policies.isEmpty()) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_NOT_FOUND);
        }
        if (policies.size() != 1 || policies.getFirst().policyVersionId() == null) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
        }
        return policies.getFirst();
    }

    private String payloadHash(Long employeeId, ManualLeaveGrantRequest request) {
        // 마지막 필드인 사유는 원문을 보존하고, 시각은 동일 instant를 같은 요청으로 취급한다.
        String content = employeeId + ":" + request.balanceTypeId() + ":" + request.grantMode() + ":"
                + request.grantMinutes() + ":" + request.validFrom().toInstant() + ":"
                + (request.expiresOn() == null ? "null" : request.expiresOn().toInstant()) + ":" + request.reason();
        return HexFormat.of().formatHex(digest(content));
    }

    private byte[] digest(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
