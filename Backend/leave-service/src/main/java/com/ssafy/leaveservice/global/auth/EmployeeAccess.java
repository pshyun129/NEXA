package com.ssafy.leaveservice.global.auth;

public interface EmployeeAccess {
    Long currentEmployeeId();

    boolean isAdmin(Long employeeId);
}
