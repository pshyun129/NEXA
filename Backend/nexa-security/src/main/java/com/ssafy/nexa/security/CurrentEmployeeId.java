package com.ssafy.nexa.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.*;

/** 컨트롤러 파라미터에 요청한 사원 ID(access 토큰의 sub)를 넣는다. 예: {@code @CurrentEmployeeId Long employeeId} */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "T(java.lang.Long).valueOf(subject)")
public @interface CurrentEmployeeId {
}
