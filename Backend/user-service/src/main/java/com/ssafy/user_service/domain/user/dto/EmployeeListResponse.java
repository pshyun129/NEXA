package com.ssafy.user_service.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 사원 목록.
 *
 * @param totalCount 조건에 맞는 전체 사원 수 (페이지로 나눠도 전체 기준)
 * @param page       요청한 페이지(0부터). 페이지를 요청하지 않았으면 응답에 없다
 * @param size       페이지 크기. 페이지를 요청하지 않았으면 응답에 없다
 */
public record EmployeeListResponse(
        List<EmployeeInfoResponse> employees,
        long totalCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer page,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer size
) {
    /** 페이지 없이 전부 */
    public static EmployeeListResponse all(List<EmployeeInfoResponse> employees) {
        return new EmployeeListResponse(employees, employees.size(), null, null);
    }
}
