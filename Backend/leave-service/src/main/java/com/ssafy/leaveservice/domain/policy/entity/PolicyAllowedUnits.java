package com.ssafy.leaveservice.domain.policy.entity;

import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;

public enum PolicyAllowedUnits {
    FULL,
    HALF,
    HOUR,
    MIXED;

    public boolean isCompatibleWith(RequestUnit requestUnit) {
        return switch (requestUnit) {
            case DAY -> this == FULL;
            case HALF_DAY -> this == HALF;
            case HOUR -> this == HOUR;
            case MIXED -> true;
        };
    }
}
