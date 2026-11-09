package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalClient;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalCommand;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@TestConfiguration(proxyBeanMethods = false)
@Profile("test & !prod & !production")
public class FakeApprovalWithdrawalClientConfiguration {
    @Bean
    public FakeApprovalWithdrawalClient fakeApprovalWithdrawalClient() {
        return new FakeApprovalWithdrawalClient();
    }

    /** 외부 HTTP 계약이 아닌 오케스트레이션 테스트 전용 대역. */
    public static final class FakeApprovalWithdrawalClient implements ApprovalWithdrawalClient {
        public final AtomicInteger calls = new AtomicInteger();
        public final AtomicInteger lookups = new AtomicInteger();
        public final List<ApprovalWithdrawalCommand> commands = new CopyOnWriteArrayList<>();
        public volatile Function<ApprovalWithdrawalCommand, ApprovalWithdrawalResult> behavior;
        public volatile Function<UUID, ApprovalWithdrawalResult> lookup;

        public void reset() {
            calls.set(0);
            lookups.set(0);
            commands.clear();
            behavior = command -> new ApprovalWithdrawalResult(command.withdrawalRequestId(), command.documentId(),
                    ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED);
            lookup = operationId -> new ApprovalWithdrawalResult(operationId, null, ApprovalWithdrawalResult.Outcome.UNKNOWN);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public ApprovalWithdrawalResult withdraw(ApprovalWithdrawalCommand command) {
            requireNoTransaction();
            calls.incrementAndGet();
            commands.add(command);
            return behavior.apply(command);
        }

        @Override
        public ApprovalWithdrawalResult findByRequestId(UUID withdrawalRequestId) {
            requireNoTransaction();
            lookups.incrementAndGet();
            return lookup.apply(withdrawalRequestId);
        }

        private void requireNoTransaction() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("외부 철회 호출 중 DB 트랜잭션이 남아 있습니다.");
            }
        }
    }
}
