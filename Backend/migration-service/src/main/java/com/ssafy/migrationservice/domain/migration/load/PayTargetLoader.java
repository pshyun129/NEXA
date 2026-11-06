package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * payroll-service의 이관용 내부 API 호출 (payroll InternalPayMigrationController).
 * grades(직급별 기본급, 지난 급여에서 뽑은 PAY_PROFILE) → payslips(지난 급여명세서) 순으로 넣는다.
 * 직급·사원은 user-service ID로 보내야 해서 인사 쪽이 먼저 들어가 있어야 한다.
 */
@Component
public class PayTargetLoader extends InternalApiLoader {

    public PayTargetLoader(@Value("${migration.targets.pay.base-url}") String baseUrl) {
        super(baseUrl, "/internal/v1/pay/migration", Map.of(
                MigrationEntity.PAY_PROFILE, "grades",
                MigrationEntity.PAY_RESULT_HISTORY, "payslips"), "급여 서비스");
    }
}
