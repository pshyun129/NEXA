package com.ssafy.user_service.global.security;

/** 토큰 상태 저장소. 토큰 원문이 아니라 jti만 다룬다. 구현은 Redis(RedisTokenStore). */
public interface TokenStore {
    void saveRefreshToken(Long employeeId, String tokenId, long ttlMillis);

    /**
     * 저장된 refresh jti가 expectedId와 같을 때만 newId로 교체한다(원자적 compare-and-set).
     * 저장값이 있는데 다르면 이미 회전된 토큰의 재사용(탈취 의심)으로 보고 세션(RT)을 삭제한다.
     * @return 교체 성공 여부
     */
    boolean rotateRefreshToken(Long employeeId, String expectedId, String newId, long ttlMillis);

    void deleteRefreshToken(Long employeeId);
    void blacklistAccessToken(String tokenId, long ttlMillis);
    boolean isBlacklisted(String tokenId);
}
