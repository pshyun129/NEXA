package com.ssafy.leaveservice.client.attendance;

import com.ssafy.leaveservice.client.LeaveRestClientFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;

@Component
public class RestHolidayClient implements HolidayClient {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RestHolidayClient(
            AttendanceHolidayProperties properties,
            LeaveRestClientFactory clientFactory,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.restClient = clientFactory.create(
                properties.baseUrl(), properties.serviceId(), properties.connectTimeout(), properties.readTimeout());
    }

    @Override
    public HolidayResult findHolidays(LocalDate startDate, LocalDate endDate) {
        try {
            // 현재 직원의 Bearer JWT를 전달하며 인증·조회 실패는 검증 불가로 처리한다.
            ResponseEntity<String> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/attn/holidays").queryParam("startDate", startDate)
                            .queryParam("endDate", endDate).build())
                    .retrieve().toEntity(String.class);
            if (response.getStatusCode().value() != 200 || response.getBody() == null) {
                return HolidayResult.unavailable();
            }
            return parseResponse(response.getBody(), startDate, endDate);
        } catch (RuntimeException exception) {
            // 외부 장애를 공휴일 없음으로 바꾸지 않는다. 인증/응답 본문은 기록하지 않는다.
            return HolidayResult.unavailable();
        }
    }

    private HolidayResult parseResponse(String body, LocalDate startDate, LocalDate endDate) {
        try {
            JsonNode response = objectMapper.readTree(body);
            if (response == null || !response.isObject() || !response.path("success").isBoolean()
                    || !response.path("success").asBoolean() || !response.path("error").isNull()
                    || !response.path("data").isArray()) {
                return HolidayResult.unavailable();
            }
            List<Holiday> holidays = new ArrayList<>();
            Set<LocalDate> seen = new HashSet<>();
            for (JsonNode item : response.get("data")) {
                if (!item.isObject() || !item.path("holidayDate").isString() || !item.path("name").isString()
                        || item.path("name").asString().isBlank() || !item.path("type").isString()) {
                    return HolidayResult.unavailable();
                }
                LocalDate date = LocalDate.parse(item.get("holidayDate").asString());
                HolidayType type = HolidayType.valueOf(item.get("type").asString());
                if (date.isBefore(startDate) || date.isAfter(endDate) || !seen.add(date)) {
                    return HolidayResult.unavailable();
                }
                holidays.add(new Holiday(date, item.get("name").asString(), type));
            }
            return new HolidayResult(true, List.copyOf(holidays));
        } catch (RuntimeException exception) {
            return HolidayResult.unavailable();
        }
    }
}
