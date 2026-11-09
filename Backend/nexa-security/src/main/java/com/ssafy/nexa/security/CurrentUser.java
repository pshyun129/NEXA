package com.ssafy.nexa.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.*;

/** 컨트롤러 파라미터에 요청한 사원(AuthUser)을 넣는다. 예: {@code @CurrentUser AuthUser user} */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "T(com.ssafy.nexa.security.AuthUser).from(#this)")
public @interface CurrentUser {
}
