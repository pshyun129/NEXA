package com.ssafy.user_service.domain.auth.controller;

import com.ssafy.user_service.domain.auth.dto.LoginRequest;
import com.ssafy.user_service.domain.auth.dto.LoginResponse;
import com.ssafy.user_service.domain.auth.dto.TokenResponse;
import com.ssafy.user_service.domain.auth.service.AuthService;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.SecurityConstants;
import com.ssafy.user_service.global.security.TokenCookieManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.AUTH_BASE_PATH)
public class AuthController {

    private final AuthService authService;
    private final TokenCookieManager cookieManager;

    @PostMapping("/login")
    public ResponseEntity<ApiResult<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        TokenResponse data = authService.login(request);
        cookieManager.setTokenCookies(response, data.accessToken(), data.refreshToken());
        return ResponseEntity.ok(ApiResult.success(
                new LoginResponse(data.employeeId(), data.deptName(), data.gradeName())));
    }

    // permitAll: access가 만료된 뒤에도 refresh 세션을 지우고 쿠키를 정리할 수 있어야 한다
    @PostMapping("/logout")
    public ResponseEntity<ApiResult<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(cookieManager.readAccessToken(request), cookieManager.readRefreshToken(request));
        cookieManager.clearTokenCookies(response);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @PostMapping("/reissue")
    public ResponseEntity<ApiResult<Void>> reissue(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieManager.readRefreshToken(request);
        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_MISSING);
        }
        TokenResponse data = authService.reissue(refreshToken, cookieManager.readAccessToken(request));
        cookieManager.setTokenCookies(response, data.accessToken(), data.refreshToken());
        return ResponseEntity.ok(ApiResult.success(null));
    }
}
