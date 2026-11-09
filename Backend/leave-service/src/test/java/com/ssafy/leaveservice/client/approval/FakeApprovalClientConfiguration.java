package com.ssafy.leaveservice.client.approval;

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
public class FakeApprovalClientConfiguration {
    @Bean
    public FakeApprovalClient fakeApprovalClient() {
        return new FakeApprovalClient();
    }

    /** 외부 계약을 구현하지 않는 테스트 전용 대역. 운영 artifact에는 포함되지 않는다. */
    public static final class FakeApprovalClient implements ApprovalClient {
        public final AtomicInteger calls = new AtomicInteger();
        public final AtomicInteger lookups = new AtomicInteger();
        public final List<ApprovalCreateCommand> commands = new CopyOnWriteArrayList<>();
        public volatile Function<ApprovalCreateCommand, ApprovalCreationResult> behavior;

        public void reset() {
            calls.set(0);
            lookups.set(0);
            commands.clear();
            behavior = command -> new ApprovalCreationResult(command.approvalRequestId(),
                    ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED, 901L);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public ApprovalCreationResult create(ApprovalCreateCommand command) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("외부 호출에 DB 트랜잭션이 남아 있습니다.");
            }
            calls.incrementAndGet();
            commands.add(command);
            return behavior.apply(command);
        }

        @Override
        public ApprovalCreationResult findByRequestId(UUID approvalRequestId) {
            lookups.incrementAndGet();
            return new ApprovalCreationResult(approvalRequestId, ApprovalCreationResult.Outcome.UNKNOWN, null);
        }
    }
}
