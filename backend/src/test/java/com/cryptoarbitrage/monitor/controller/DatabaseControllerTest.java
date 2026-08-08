package com.cryptoarbitrage.monitor.controller;

import com.cryptoarbitrage.monitor.dto.DatabaseFlushResultDto;
import com.cryptoarbitrage.monitor.dto.DatabaseStatsDto;
import com.cryptoarbitrage.monitor.service.DatabaseAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DatabaseController.class)
class DatabaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatabaseAdminService databaseAdminService;

    @Test
    void getStats_returnsDatabaseStats() throws Exception {
        when(databaseAdminService.getStats()).thenReturn(new DatabaseStatsDto(
                12_582_912L,
                "12 MB",
                1500L,
                1_048_576L,
                "1024 kB"
        ));

        mockMvc.perform(get("/api/database/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(12582912))
                .andExpect(jsonPath("$.sizePretty").value("12 MB"))
                .andExpect(jsonPath("$.spreadLogRows").value(1500))
                .andExpect(jsonPath("$.spreadLogBytes").value(1048576))
                .andExpect(jsonPath("$.spreadLogSizePretty").value("1024 kB"));
    }

    @Test
    void flushSpreadLog_returnsDeletedCountAndFreshStats() throws Exception {
        DatabaseStatsDto stats = new DatabaseStatsDto(8_388_608L, "8 MB", 0L, 8192L, "8192 bytes");
        when(databaseAdminService.flushSpreadLog()).thenReturn(new DatabaseFlushResultDto(1500L, stats));

        mockMvc.perform(delete("/api/database/spread-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedRows").value(1500))
                .andExpect(jsonPath("$.stats.spreadLogRows").value(0))
                .andExpect(jsonPath("$.stats.sizePretty").value("8 MB"));

        verify(databaseAdminService).flushSpreadLog();
    }
}
