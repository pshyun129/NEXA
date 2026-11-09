package com.ssafy.leaveservice.global.auth;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface LeaveAdministratorOnly {
}
