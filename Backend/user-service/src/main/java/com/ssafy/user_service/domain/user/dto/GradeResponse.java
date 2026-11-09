package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.Grade;

/** 직급 목록 항목. 같은 직급명이 호봉(level)별로 여러 행이다 (예: G1 1호봉 ~ G1 5호봉) */
public record GradeResponse(
        Long gradeId,
        String name,            // 직급명
        Short level             // 호봉
) {
    public static GradeResponse from(Grade grade) {
        return new GradeResponse(grade.getGradeId(), grade.getName(), grade.getLevel());
    }
}
