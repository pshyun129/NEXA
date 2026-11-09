package com.ssafy.user_service.global.security;

/**
 * 시스템 권한. Spring Security authority로는 "ROLE_" 접두어가 붙는다 (ROLE_ADMIN).
 */
public enum Role {
    USER,   // 일반 사원
    ADMIN   // 관리자 (사원 등록 등 /api/v1/admin/** 접근)
}
