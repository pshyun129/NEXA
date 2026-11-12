package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.client.attendance.AttendanceRevertClient;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertCommand;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertResult;
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
public class FakeAttendanceRevertClientConfiguration {
    @Bean
    public FakeAttendanceRevertClient fakeAttendanceRevertClient() {
        return new FakeAttendanceRevertClient();
    }

    /** 외부 HTTP 계약이 아닌 오케스트레이션 테스트 전용 대역. */
    public static final class FakeAttendanceRevertClient implements AttendanceRevertClient {
        public final AtomicInteger calls = new AtomicInteger();
        public final AtomicInteger lookups = new AtomicInteger();
        public final List<AttendanceRevertCommand> commands = new CopyOnWriteArrayList<>();
        public volatile Function<AttendanceRevertCommand, AttendanceRevertResult> behavior;
        public volatile Function<AttendanceRevertCommand, AttendanceRevertResult> lookup;

        public void reset() {
            calls.set(0);
            lookups.set(0);
            commands.clear();
            behavior = command -> new AttendanceRevertResult(command.requestId(), command.cancelRequestId(),
                    AttendanceRevertResult.Outcome.REVERTED_CONFIRMED);
            lookup = command -> new AttendanceRevertResult(command.requestId(), command.cancelRequestId(), AttendanceRevertResult.Outcome.UNKNOWN);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AttendanceRevertResult revert(AttendanceRevertCommand command) {
            requireNoTransaction();
            calls.incrementAndGet();
            commands.add(command);
            return behavior.apply(command);
        }

        @Override
        public AttendanceRevertResult recover(AttendanceRevertCommand command) {
            requireNoTransaction();
            lookups.incrementAndGet();
            return lookup.apply(command);
        }

        private void requireNoTransaction() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("외부 근태 원복 호출 중 DB 트랜잭션이 남아 있습니다.");
            }
        }
    }
}
