package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess;
import com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@Configuration(proxyBeanMethods = false)
public class LeaveAdministratorAuthorizationConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DefaultPointcutAdvisor leaveAdministratorAdvisor(
            ObjectProvider<LeaveAdministratorAccess> access,
            ObjectProvider<EmployeeAccess> employeeAccess
    ) {
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return Modifier.isPublic(method.getModifiers()) && method.getDeclaringClass() != Object.class
                        && (AnnotatedElementUtils.hasAnnotation(targetClass, LeaveAdministratorOnly.class)
                        || AnnotatedElementUtils.hasAnnotation(method, LeaveAdministratorOnly.class));
            }
        };
        MethodInterceptor authorization = invocation -> access.getObject().authorize(employeeAccess.getObject(), invocation);
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, authorization);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }
}
