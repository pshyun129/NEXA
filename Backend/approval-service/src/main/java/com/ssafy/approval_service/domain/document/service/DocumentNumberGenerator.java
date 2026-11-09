package com.ssafy.approval_service.domain.document.service;

import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 양식·연도별 문서 일련번호 채번 (document_number_counter).
 * 카운터 행이 없으면 1로 만들고, 있으면 1 올린 값을 돌려준다. 한 문장이라 그 해 첫 문서가 동시에 생성돼도 겹치지 않는다.
 * 올린 행은 호출한 트랜잭션이 끝날 때까지 잠기므로 같은 양식·연도 문서는 순서대로 번호를 받고,
 * 문서 생성이 롤백되면 카운터도 함께 롤백되어 번호가 비지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DocumentNumberGenerator {

    private static final String NEXT_SQL = """
            INSERT INTO document_number_counter (form_type, year, last_no)
            VALUES (?, ?, 1)
            ON CONFLICT (form_type, year)
            DO UPDATE SET last_no = document_number_counter.last_no + 1
            RETURNING last_no
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 다음 일련번호. 문서 생성과 같은 트랜잭션에서 호출해야 한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int next(FormType formType, int year) {
        Integer next = jdbcTemplate.queryForObject(NEXT_SQL, Integer.class, formType.name(), year);
        if (next == null) {
            throw new IllegalStateException("문서번호를 만들지 못했습니다: " + formType + " " + year);
        }
        return next;
    }
}
