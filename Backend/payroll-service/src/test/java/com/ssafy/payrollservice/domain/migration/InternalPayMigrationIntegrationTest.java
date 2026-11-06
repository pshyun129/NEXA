package com.ssafy.payrollservice.domain.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** migration-service 용 급여 이관 내부 API. 토큰 없이 부른다 (migration RestClient 와 같음) */
@SpringBootTest(properties = {"spring.config.location=classpath:/payroll-ci.yml"})
@AutoConfigureMockMvc
@Testcontainers
class InternalPayMigrationIntegrationTest {

    private static final String BASE = "/internal/v1/pay/migration";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payslip_item");
        jdbc.update("DELETE FROM payslip");
        jdbc.update("DELETE FROM pay_grade");
        jdbc.update("DELETE FROM migration_batch_row");
    }

    private ResultActions upsert(String entity, String batchId, String rowsJson) throws Exception {
        return mvc.perform(post(BASE + "/{entity}/upsert", entity).param("batchId", batchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + rowsJson + "]}"));
    }

    private static String payslip(int rowNo, long empId, int month, String items) {
        return """
                {"rowNo":%d,"data":{"empId":%d,"payYear":2026,"payMonth":%d,"empName":"이관사원","deptName":"인사팀",
                 "positionName":"대리","hireDate":"2020-03-02","otExtendedMin":480,
                 "totalPay":4230000,"totalDeduction":430000,"netPay":3800000,"items":%s}}"""
                .formatted(rowNo, empId, month, items);
    }

    private static final String ITEMS = """
            {"BASE_PAY":3800000,"POSITION_ALLOWANCE":0,"MEAL_ALLOWANCE":200000,"OT_EXTENDED_PAY":230000,
             "NATIONAL_PENSION":180000,"HEALTH_INSURANCE":140000,"INCOME_TAX":110000}""";

    @Test
    void 직급별_기본급은_없는_직급만_만들고_되돌리면_만든_것만_지운다() throws Exception {
        jdbc.update("INSERT INTO pay_grade (grade_id, base_amount) VALUES (1, 3500000)");

        upsert("grades", "b-pay-grade", """
                {"rowNo":1,"data":{"gradeId":1,"baseAmount":9999999}},
                {"rowNo":2,"data":{"gradeId":2,"baseAmount":3600000}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(1));
        // 있던 직급 1은 그대로
        assertThat(jdbc.queryForObject("SELECT base_amount FROM pay_grade WHERE grade_id = 1", Integer.class)).isEqualTo(3500000);
        mvc.perform(get(BASE + "/batches/b-pay-grade/summary")).andExpect(jsonPath("$.data.count").value(2));

        mvc.perform(delete(BASE + "/batches/b-pay-grade")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT grade_id FROM pay_grade", Long.class)).containsExactly(1L);
        mvc.perform(get(BASE + "/batches/b-pay-grade/summary")).andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void 지난_명세서는_급여대장_값_그대로_저장하고_되돌리면_항목까지_지운다() throws Exception {
        upsert("payslips", "b-pay-slip", payslip(3, 101, 10, ITEMS) + "," + payslip(4, 101, 11, ITEMS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(2));

        Map<String, Object> slip = jdbc.queryForMap("SELECT * FROM payslip WHERE emp_id = 101 AND pay_month = 11");
        assertThat(slip).containsEntry("net_pay", 3800000).containsEntry("ot_extended_min", 480)
                .containsEntry("work_days", 0).containsEntry("dept_name", "인사팀");
        // 0원 항목(직책수당)은 넣지 않는다
        assertThat(jdbc.queryForList("""
                SELECT pi.item_code FROM payslip_item psi JOIN pay_item pi ON pi.item_id = psi.item_id
                WHERE psi.payslip_id = ? ORDER BY pi.sort_order""", String.class, slip.get("payslip_id")))
                .containsExactly("BASE_PAY", "OT_EXTENDED_PAY", "MEAL_ALLOWANCE", "INCOME_TAX", "NATIONAL_PENSION", "HEALTH_INSURANCE");
        mvc.perform(get(BASE + "/batches/b-pay-slip/summary")).andExpect(jsonPath("$.data.count").value(2));

        mvc.perform(delete(BASE + "/batches/b-pay-slip")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payslip", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payslip_item", Long.class)).isZero();
    }

    @Test
    void 같은_사원_같은_달_명세서가_있으면_행번호와_함께_실패하고_그_요청분은_저장되지_않는다() throws Exception {
        upsert("payslips", "b-pay-1", payslip(2, 201, 11, ITEMS)).andExpect(status().isOk());

        upsert("payslips", "b-pay-2", payslip(5, 202, 11, ITEMS) + "," + payslip(6, 201, 11, ITEMS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MIG_001"))
                .andExpect(jsonPath("$.error.message").value(containsString("행 6")))
                .andExpect(jsonPath("$.error.message").value(containsString("이미 있습니다")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payslip WHERE emp_id = 202", Long.class)).isZero();
    }

    @Test
    void 모르는_급여_항목_코드는_400() throws Exception {
        upsert("payslips", "b-pay-bad", payslip(7, 301, 11, "{\"BASE_PAY\":100,\"SECRET_PAY\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("SECRET_PAY")));
    }

    @Test
    void 모르는_이관_대상은_404() throws Exception {
        upsert("orgs", "b-pay-x", "").andExpect(status().isNotFound());
    }
}
