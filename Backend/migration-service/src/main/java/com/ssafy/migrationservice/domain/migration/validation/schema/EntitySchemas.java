package com.ssafy.migrationservice.domain.migration.validation.schema;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ssafy.migrationservice.domain.migration.validation.schema.FieldType.DATE;
import static com.ssafy.migrationservice.domain.migration.validation.schema.FieldType.EMAIL;
import static com.ssafy.migrationservice.domain.migration.validation.schema.FieldType.ENUM;
import static com.ssafy.migrationservice.domain.migration.validation.schema.FieldType.NUMBER;
import static com.ssafy.migrationservice.domain.migration.validation.schema.FieldType.PHONE;
import static com.ssafy.migrationservice.domain.migration.validation.schema.FieldType.STRING;

/**
 * entity별 필드 정의.
 * 지금은 user-service의 EmployeeCreateRequest·employee 테이블 NOT NULL 기준으로 여기 직접 둔다.
 * 각 서비스에 스키마 API가 생기면 그쪽에서 받아오도록 바꾼다.
 */
public final class EntitySchemas {

    private static final Map<MigrationEntity, EntitySchema> SCHEMAS = new EnumMap<>(MigrationEntity.class);

    static {
        SCHEMAS.put(MigrationEntity.HR_EMPLOYEE, new EntitySchema(MigrationEntity.HR_EMPLOYEE, List.of(
                FieldSpec.of("empNo", "사번", STRING).key().maxLength(50).synonyms("사원번호", "직원번호", "사번코드", "사원코드", "empno").build(),
                FieldSpec.of("name", "이름", STRING).required().synonyms("성명", "사원명", "직원명", "name", "empname").build(),
                FieldSpec.of("engName", "영문이름", STRING).synonyms("영문명", "영문성명", "englishname").build(),
                FieldSpec.of("email", "이메일", EMAIL).required().synonyms("메일", "이메일주소", "로그인id", "email").build(),
                FieldSpec.of("dept", "부서", STRING).required().synonyms("부서명", "소속", "소속부서", "부서코드").build(),
                FieldSpec.of("position", "직위", STRING).required().codeLike().synonyms("직위명").build(),
                FieldSpec.of("grade", "직급", STRING).required().codeLike().synonyms("직급명").build(),
                // NEXA 직급은 호봉이 꼭 있다. 직급 열과 따로 적힌 호봉은 적재할 때 직급과 합친다 ('2급' + 27 → '2급 27호봉')
                FieldSpec.of("gradeStep", "호봉", NUMBER).synonyms("호봉수", "현호봉", "step", "gradestep", "gradelevel").build(),
                FieldSpec.of("duty", "직책", STRING).required().codeLike().synonyms("직책명").build(),
                FieldSpec.of("jobFamily", "직군", STRING).required().codeLike().build(),
                FieldSpec.of("jobKind", "직종", STRING).required().codeLike().build(),
                FieldSpec.of("jobRole", "직무", STRING).required().build(),
                FieldSpec.of("empType", "직원구분", ENUM).synonyms("고용형태", "근로형태")
                        .enumValues(Map.of("정규직", "REGULAR", "파트타임", "PARTTIME", "계약직", "CONTRACT")).build(),
                FieldSpec.of("hireType", "채용구분", ENUM).synonyms("채용유형")
                        .enumValues(Map.of("공개채용", "OPEN", "수시채용", "ROLL", "추천채용", "RECOMMENDATION")).build(),
                FieldSpec.of("hireDate", "입사일", DATE).required().synonyms("입사일자", "입사년월일", "최초입사일").build(),
                FieldSpec.of("recogHireDate", "인정입사일", DATE).synonyms("그룹입사일", "인정입사일자").build(),
                FieldSpec.of("birthDate", "생년월일", DATE).synonyms("생일").build(),
                FieldSpec.of("gender", "성별", ENUM).enumValues(Map.of("남", "MALE", "남자", "MALE", "여", "FEMALE", "여자", "FEMALE")).build(),
                FieldSpec.of("mobile", "휴대전화", PHONE).synonyms("휴대폰", "핸드폰", "연락처", "휴대폰번호").build(),
                FieldSpec.of("officePhone", "직통번호", PHONE).synonyms("사내전화", "내선번호", "회사전화").build(),
                FieldSpec.of("nationality", "국적", STRING).build(),
                FieldSpec.of("address", "주소", STRING).synonyms("거주지").build(),
                FieldSpec.of("status", "재직상태", ENUM).synonyms("재직구분", "상태")
                        .enumValues(Map.of("재직", "EMPLOYED", "휴직", "ON_LEAVE", "퇴직", "TERMINATED", "퇴사", "TERMINATED")).build(),
                FieldSpec.of("resignDate", "퇴사일", DATE).synonyms("퇴직일", "퇴사일자").build()
        )));

        SCHEMAS.put(MigrationEntity.HR_ORG, new EntitySchema(MigrationEntity.HR_ORG, List.of(
                FieldSpec.of("deptCode", "부서코드", STRING).key().maxLength(50).synonyms("조직코드", "부서id").build(),
                FieldSpec.of("name", "부서명", STRING).required().synonyms("조직명", "부서", "deptname", "orgname").build(),
                FieldSpec.of("parentDeptCode", "상위부서", STRING)
                        .synonyms("상위부서코드", "상위조직", "상위조직코드", "parentcode", "parentdept", "upperdeptcode", "parentorgcode").build(),
                FieldSpec.of("sortOrder", "정렬순서", NUMBER).synonyms("순서", "표시순서").build(),
                // user-service 부서 유형(DeptType)·부서장(head_emp_id). 결재선 자동 결정에 쓰인다
                FieldSpec.of("deptType", "부서유형", ENUM).synonyms("조직유형", "부서구분", "조직구분", "depttype", "orgtype")
                        .enumValues(Map.of("회사", "COMPANY", "본부", "DIVISION", "팀", "TEAM")).build(),
                FieldSpec.of("headEmpNo", "부서장 사번", STRING).maxLength(50)
                        .synonyms("부서장", "부서장사번", "조직장", "조직장사번", "headempno", "deptheadempno", "managerempno").build()
        )));

        // 지난 급여(payroll 급여명세서). 한 행 = 한 사람의 한 달. 귀속월 열이 없으면 시트·파일 이름(2026-11)에서 읽는다.
        // 금액 필드는 payroll pay_item 코드와 1:1 (PayTargetLoader). 매핑되지 않은 지급·공제는 지급계·공제계와의 차이로 기타수당·기타공제가 된다
        SCHEMAS.put(MigrationEntity.PAY_RESULT_HISTORY, new EntitySchema(MigrationEntity.PAY_RESULT_HISTORY, List.of(
                FieldSpec.of("empNo", "사번", STRING).required().maxLength(50).synonyms("사원번호", "직원번호", "사원코드", "empno").build(),
                FieldSpec.of("name", "성명", STRING).synonyms("이름", "사원명", "직원명", "name").build(),
                FieldSpec.of("payMonth", "귀속월", STRING).synonyms("귀속연월", "지급월", "급여월", "지급연월", "paymonth", "yearmonth").build(),
                FieldSpec.of("payDate", "지급일", DATE).synonyms("지급일자", "paydate").build(),
                FieldSpec.of("basePay", "기본급", NUMBER).required().synonyms("봉급", "basepay", "basesalary").build(),
                FieldSpec.of("positionAllowance", "직책수당", NUMBER).synonyms("직무수당", "보직수당", "positionallowance").build(),
                FieldSpec.of("mealAllowance", "식대", NUMBER).synonyms("식비", "중식대", "급식비", "mealallowance", "meal").build(),
                FieldSpec.of("otExtendedHours", "연장근로시간", NUMBER).synonyms("연장시간", "시간외근로시간", "othours", "overtimehours").build(),
                FieldSpec.of("otExtendedPay", "연장근로수당", NUMBER).synonyms("연장수당", "시간외수당", "시간외근무수당", "otpay", "overtimepay").build(),
                FieldSpec.of("otNightPay", "야간근로수당", NUMBER).synonyms("야간수당", "nightpay").build(),
                FieldSpec.of("otHolidayPay", "휴일근로수당", NUMBER).synonyms("휴일수당", "휴일근무수당", "holidaypay").build(),
                FieldSpec.of("annualLeavePay", "연차수당", NUMBER).synonyms("연차미사용수당", "annualleavepay").build(),
                FieldSpec.of("bonus", "상여금", NUMBER).synonyms("상여", "성과급", "bonus").build(),
                FieldSpec.of("totalPay", "지급계", NUMBER).required().synonyms("지급합계", "지급총액", "총지급액", "급여총액", "grosspay", "gross").build(),
                FieldSpec.of("incomeTax", "소득세", NUMBER).synonyms("근로소득세", "갑근세", "incometax").build(),
                FieldSpec.of("localIncomeTax", "지방소득세", NUMBER).synonyms("주민세", "근로지방소득세", "localtax").build(),
                FieldSpec.of("nationalPension", "국민연금", NUMBER).synonyms("연금", "nationalpension", "np").build(),
                FieldSpec.of("healthInsurance", "건강보험", NUMBER).synonyms("건강보험료", "healthinsurance", "hi").build(),
                FieldSpec.of("longTermCare", "장기요양", NUMBER).synonyms("장기요양보험", "장기요양보험료", "longtermcare", "ltc").build(),
                FieldSpec.of("employmentInsurance", "고용보험", NUMBER).synonyms("고용보험료", "employmentinsurance", "ei").build(),
                FieldSpec.of("totalDeduction", "공제계", NUMBER).required().synonyms("공제합계", "공제총액", "총공제액", "totaldeduction").build(),
                FieldSpec.of("netPay", "실지급액", NUMBER).required().synonyms("차인지급액", "실수령액", "차감지급액", "netpay", "net").build()
        )));
    }

    private EntitySchemas() {
    }

    /** 아직 정의가 없는 entity는 비어 있음 (검증 건너뜀) */
    public static Optional<EntitySchema> of(MigrationEntity entity) {
        return Optional.ofNullable(SCHEMAS.get(entity));
    }
}
