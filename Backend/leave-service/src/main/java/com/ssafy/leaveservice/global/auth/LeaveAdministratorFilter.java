package com.ssafy.leaveservice.global.auth;

import com.ssafy.leaveservice.global.dto.ApiResult;
import com.ssafy.leaveservice.global.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@RequiredArgsConstructor
public class LeaveAdministratorFilter extends OncePerRequestFilter {
    private final LeaveAdministratorAccess administratorAccess;
    private final EmployeeAccess employeeAccess;
    private final JsonMapper jsonMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/v1/leaves/admin") && !path.startsWith("/api/v1/leaves/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)
                || !token.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            // MVC 입력 검증과 업무 트랜잭션보다 먼저 권한을 확인하고 호출 종료 시 증거를 정리한다.
            administratorAccess.authorize(employeeAccess, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (BusinessException failure) {
            response.setStatus(failure.getErrorCode().getStatus().value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            jsonMapper.writeValue(response.getOutputStream(), ApiResult.fail(failure.getErrorCode()));
        } catch (IOException | ServletException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ServletException(failure);
        }
    }
}
