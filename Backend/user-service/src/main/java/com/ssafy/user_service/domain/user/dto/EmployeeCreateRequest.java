package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.enums.EmpType;
import com.ssafy.user_service.domain.user.entity.enums.Gender;
import com.ssafy.user_service.domain.user.entity.enums.HireType;
import com.ssafy.user_service.global.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 사원 계정 등록 요청 (관리자 기능).
 * 필수 항목은 employee 테이블의 NOT NULL 컬럼과 로그인 필수값 기준이며 @Valid 로 검증된다.
 */
public record EmployeeCreateRequest(
        @NotBlank @Email String email,          // 로그인 ID (login_id)
        @NotBlank @Size(min = 8, max = 72) String password, // 평문 비밀번호 (BCrypt 입력 한계 72바이트)
        @NotBlank String name,                  // 이름
        String engName,                         // 영문이름
        String empNo,                           // 사원번호
        @NotNull Long deptId,                   // 부서번호
        @NotNull Long positionId,               // 직위번호
        @NotNull Long gradeId,                  // 직급번호
        @NotNull Long dutyId,                   // 직책번호
        @NotNull Long jobFamilyId,              // 직군번호
        @NotNull Long jobKindId,                // 직종번호
        @NotNull Long jobRoleId,                // 직무번호
        EmpType empType,                        // 직원구분
        HireType hireType,                      // 채용구분
        LocalDate hireDate,                     // 입사일 (yyyy-MM-dd)
        LocalDate recogHireDate,                // 인정입사일
        String officePhone,                     // 직통번호
        String mobile,                          // 휴대전화
        String nationality,                     // 국적
        Gender gender,                          // 성별
        LocalDate birthDate,                    // 생년월일
        String address,                         // 주소
        Long leavePolicyId,                     // 연차정책ID
        Long workGroupId,                       // 근무그룹ID
        Role role                               // 시스템 권한 (null이면 USER)
) {
}
