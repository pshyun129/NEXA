package com.ssafy.payrollservice.global.util;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class DateTimeUtils {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private DateTimeUtils() {
    }

    /** 현재 시각 (Asia/Seoul, DB 저장 정밀도에 맞춰 마이크로초까지) */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    /** 응답용 시각을 Asia/Seoul(+09:00)로 변환 */
    public static OffsetDateTime toSeoul(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(ZONE).toOffsetDateTime();
    }
}