package com.ssafy.user_service.domain.auth.service;

import com.ssafy.user_service.domain.auth.dto.LoginRequest;
import com.ssafy.user_service.domain.auth.dto.TokenResponse;

public interface AuthService {
    TokenResponse login(LoginRequest loginRequest);

    /**
     * 로그아웃. 두 토큰 모두 null일 수 있고 만료돼 있어도 된다.
     * access가 아직 유효하면 jti를 블랙리스트에 넣고, 어느 쪽에서든 주체를 알아내면 refresh 세션을 삭제한다.
     */
    void logout(String accessToken, String refreshToken);

    /**
     * 재발급. refresh를 검증·회전하고 새 access/refresh를 발급한다.
     * 함께 제시된 이전 access 토큰(null 가능)은 남은 수명 동안 블랙리스트에 넣는다.
     */
    TokenResponse reissue(String refreshToken, String currentAccessToken);
}
