package com.ssafy.leaveservice.domain.adjustment.service;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;
import com.ssafy.leaveservice.domain.account.service.LeaveBalanceAmounts;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentResponse;
import com.ssafy.leaveservice.domain.adjustment.repository.LeaveAdjustmentRepository;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveSubmissionLockRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeaveAdjustmentService {

    private final EmployeeAccess employeeAccess;
    private final Validator validator;
    private final LeaveSubmissionLockRepository submissionLocks;
    private final LeaveAdjustmentRepository adjustmentRepository;
    private final LeaveAccountRepository accountRepository;
    private final LeaveLedgerRepository ledgerRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeaveAdjustmentResponse adjust(
            Long employeeId,
            String requestKey,
            LeaveAdjustmentRequest request
    ) {
        // 인증된 관리자 확인
        Long actorEmpId = employeeAccess.currentEmployeeId();
        if (actorEmpId == null || actorEmpId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!employeeAccess.isAdmin(actorEmpId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 입력값 검증: 사유와 요청 키의 앞뒤 공백은 저장·비교 시 그대로 유지한다.
        if (employeeId == null || employeeId <= 0 || requestKey == null || requestKey.isBlank()
                || request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 대상 직원과 관리자별 요청 키 잠금 후 기존 성공 요청 확인
        byte[] digest = createKeyDigest(actorEmpId, requestKey);
        String ledgerKey = "leave:admin-adjust:" + HexFormat.of().formatHex(digest);
        submissionLocks.lockEmployee(employeeId);
        adjustmentRepository.lockRequestKey(digest);
        Optional<LeaveLedger> existing = ledgerRepository.findByAdjustmentKey(ledgerKey);
        if (existing.isPresent()) {
            validateSameRequest(existing.get(), employeeId, actorEmpId, request);
            return LeaveAdjustmentResponse.from(existing.get());
        }

        // 계정과 발생분 소속 확인 및 행 잠금
        // TODO HR 직원 조회 계약 확정 후 대상 직원의 실재 여부 검증 추가. 현재는 계정 소유권을 검증한다.
        if (!adjustmentRepository.lockOwnedAccount(employeeId, request.accountId())
                || !adjustmentRepository.lockOwnedGrant(request.accountId(), request.grantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        LeaveAccount account = accountRepository.findById(request.accountId()).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND)
        );

        // 모든 잠금 대기 이후 DB 시각으로 유효기간과 정상 지급 이력 확인
        OffsetDateTime asOf = submissionLocks.currentTime();
        PreviewGrantBalance grantBalance = accountRepository.findAdjustmentGrantBalance(
                employeeId, request.grantId(), asOf
        ).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!account.getBalanceType().isActive() || grantBalance.getValidFrom().isAfter(asOf)
                || (grantBalance.getExpiresOn() != null && !grantBalance.getExpiresOn().isAfter(asOf))) {
            throw new BusinessException(ErrorCode.LEAVE_ADJUSTMENT_NOT_ALLOWED);
        }

        // 기존 발생분 정합성과 조정 후 예약량 침범 여부 검증
        LeaveBalanceAmounts before = LeaveBalanceAmounts.from(
                grantBalance.getQuantityMinutes(), grantBalance.getReservedMinutes()
        );
        if (!adjustmentRepository.hasEffectiveCredit(request.accountId(), request.grantId(), asOf)) {
            throw new BusinessException(ErrorCode.LEAVE_ADJUSTMENT_NOT_ALLOWED);
        }
        BigDecimal adjustedQuantity = BigDecimal.valueOf(before.quantityMinutes())
                .add(BigDecimal.valueOf(request.quantityDelta()));
        LeaveBalanceAmounts.from(adjustedQuantity, BigDecimal.valueOf(before.reservedMinutes()));

        // 기존 원장은 유지하고 관리자 ADJUST 한 행 저장
        LeaveLedger adjustment = LeaveLedger.adjust(
                account,
                request.grantId(),
                request.quantityDelta(),
                actorEmpId,
                request.reason(),
                ledgerKey,
                asOf
        );
        LeaveLedger saved = ledgerRepository.saveAndFlush(adjustment);

        // 실제 저장된 원장 정보 반환
        return LeaveAdjustmentResponse.from(saved);
    }

    private byte[] createKeyDigest(Long actorEmpId, String requestKey) {
        try {
            String keyMaterial = "ADMIN_ADJUST:" + actorEmpId + ":" + requestKey;
            return MessageDigest.getInstance("SHA-256").digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private void validateSameRequest(
            LeaveLedger ledger,
            Long employeeId,
            Long actorEmpId,
            LeaveAdjustmentRequest request
    ) {
        if (!Objects.equals(ledger.getActorEmpId(), actorEmpId)
                || !Objects.equals(ledger.getAccount().getEmployeeId(), employeeId)
                || !Objects.equals(ledger.getAccount().getAccountId(), request.accountId())
                || !Objects.equals(ledger.getGrantId(), request.grantId())
                || ledger.getQuantityDelta() != request.quantityDelta() || ledger.getReservedDelta() != 0
                || ledger.getEventType() != LedgerEventType.ADJUST
                || !Objects.equals(ledger.getReason(), request.reason())) {
            throw new BusinessException(ErrorCode.LEAVE_ADJUSTMENT_KEY_CONFLICT);
        }
    }
}
