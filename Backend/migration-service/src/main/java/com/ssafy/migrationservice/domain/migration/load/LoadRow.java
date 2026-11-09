package com.ssafy.migrationservice.domain.migration.load;

import java.util.Map;

/** 대상 서비스로 보낼 한 행. data는 필드명 → 정리된 값 (날짜 ISO, 코드값 변환 완료) */
public record LoadRow(int rowNo, Map<String, Object> data) {
}
