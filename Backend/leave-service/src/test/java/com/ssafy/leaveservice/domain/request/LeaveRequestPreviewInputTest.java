package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.controller.LeaveRequestPreviewController;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.service.LeaveRequestPreviewService;
import com.ssafy.leaveservice.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LeaveRequestPreviewInputTest {
    private final LeaveRequestPreviewService service = mock(LeaveRequestPreviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LeaveRequestPreviewController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private static final String URL = "/api/v1/leaves/me/requests/preview";

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "null", "{", "{\"leaveTypeId\":0,\"dates\":[]}",
            "{\"leaveTypeId\":-1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"FULL\"}]}",
            "{\"leaveTypeId\":1.5,\"dates\":[]}", "{\"leaveTypeId\":\"1\",\"dates\":[]}",
            "{\"leaveTypeId\":1,\"dates\":null}", "{\"leaveTypeId\":1,\"dates\":[]}",
            "{\"leaveTypeId\":1,\"dates\":[null]}", "{\"leaveTypeId\":1,\"dates\":[{}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"bad\",\"portion\":\"FULL\"}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":null,\"portion\":\"FULL\"}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":null}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"HOURLY\"}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"HOUR\"}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"QUARTER\"}]}",
            "{\"leaveTypeId\":1,\"employeeId\":84,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"FULL\"}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"FULL\",\"minutes\":1}]}",
            "{\"leaveTypeId\":1,\"minutes\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":\"FULL\"}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-01-07\",\"portion\":0}]}",
            "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"2030-02-30\",\"portion\":\"FULL\"}]}"
    })
    void rejectsMalformedUnsupportedAndSpoofedInput(String json) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(service);
    }

    @Test
    void optionalReasonPreservedAndLengthLimitEnforced() throws Exception {
        String dates = "[{\"leaveDate\":\"2030-01-07\",\"portion\":\"AM_HALF\"}]";
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"leaveTypeId\":1,\"reason\":null,\"dates\":"+dates+"}"))
                .andExpect(status().isOk());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"leaveTypeId\":1,\"reason\":\""+"a".repeat(2000)+"\",\"dates\":"+dates+"}"))
                .andExpect(status().isOk());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"leaveTypeId\":1,\"reason\":\""+"a".repeat(2001)+"\",\"dates\":"+dates+"}"))
                .andExpect(status().isBadRequest());
        ArgumentCaptor<LeaveRequestPreviewRequest> captor = ArgumentCaptor.forClass(LeaveRequestPreviewRequest.class);
        verify(service,times(2)).preview(captor.capture());
        assertThat(captor.getAllValues().getFirst().reason()).isNull();
        assertThat(captor.getValue().reason()).hasSize(2000);
    }

    @Test
    void dateCount366Allowed367RejectedBeforeService() throws Exception {
        String date = "{\"leaveDate\":\"2030-01-07\",\"portion\":\"FULL\"}";
        for (int count : new int[]{366,367}) {
            String json = "{\"leaveTypeId\":1,\"dates\":["+String.join(",", java.util.Collections.nCopies(count,date))+"]}";
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(count == 366 ? status().isOk() : status().isBadRequest());
        }
        verify(service).preview(any());
    }
}
