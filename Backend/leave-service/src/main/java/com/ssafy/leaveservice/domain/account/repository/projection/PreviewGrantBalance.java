package com.ssafy.leaveservice.domain.account.repository.projection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;

public interface PreviewGrantBalance {
    Long getGrantId();
    Instant getValidFromInstant();
    Instant getExpiresOnInstant();

    // Hibernate의 native TIMESTAMPTZ 결과를 명시적으로 OffsetDateTime으로 변환한다.
    default OffsetDateTime getValidFrom() {
        return getValidFromInstant().atOffset(ZoneOffset.UTC);
    }

    default OffsetDateTime getExpiresOn() {
        Instant expiry = getExpiresOnInstant();
        return expiry == null ? null : expiry.atOffset(ZoneOffset.UTC);
    }
    BigDecimal getQuantityMinutes();
    BigDecimal getReservedMinutes();
}
