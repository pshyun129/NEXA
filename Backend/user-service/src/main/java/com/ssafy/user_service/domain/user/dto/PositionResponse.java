package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.Position;

/** 직위 목록 항목 */
public record PositionResponse(
        Long positionId,
        String name,            // 직위명
        Integer rankOrder       // 서열 (작을수록 아래)
) {
    public static PositionResponse from(Position position) {
        return new PositionResponse(position.getPositionId(), position.getName(), position.getRankOrder());
    }
}
