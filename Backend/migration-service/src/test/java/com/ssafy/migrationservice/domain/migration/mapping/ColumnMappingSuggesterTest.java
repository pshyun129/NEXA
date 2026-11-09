package com.ssafy.migrationservice.domain.migration.mapping;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnMappingSuggesterTest {

    @Test
    void 라벨_동의어_공백차이를_연결하고_모르는_컬럼은_비운다() {
        Map<String, String> m = ColumnMappingSuggester.suggest(
                List.of("사원번호", "성 명", "입사년월일", "E-mail", "기본급", "비고"),
                EntitySchemas.of(MigrationEntity.HR_EMPLOYEE).orElseThrow());

        assertThat(m).containsEntry("사원번호", "empNo")
                .containsEntry("성 명", "name")
                .containsEntry("입사년월일", "hireDate")
                .containsEntry("E-mail", "email")
                .containsEntry("기본급", null)
                .containsEntry("비고", null);
    }

    @Test
    void 한_필드는_먼저_나온_컬럼에만_연결한다() {
        Map<String, String> m = ColumnMappingSuggester.suggest(List.of("입사일", "입사일자"),
                EntitySchemas.of(MigrationEntity.HR_EMPLOYEE).orElseThrow());

        assertThat(m).containsEntry("입사일", "hireDate").containsEntry("입사일자", null);
    }
}
