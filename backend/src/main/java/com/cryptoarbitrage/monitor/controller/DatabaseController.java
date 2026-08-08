package com.cryptoarbitrage.monitor.controller;

import com.cryptoarbitrage.monitor.dto.DatabaseFlushResultDto;
import com.cryptoarbitrage.monitor.dto.DatabaseStatsDto;
import com.cryptoarbitrage.monitor.service.DatabaseAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/database")
public class DatabaseController {

    private final DatabaseAdminService databaseAdminService;

    public DatabaseController(DatabaseAdminService databaseAdminService) {
        this.databaseAdminService = databaseAdminService;
    }

    /**
     * GET /api/database/stats — database and spread_log size / row counts.
     */
    @GetMapping("/stats")
    public ResponseEntity<DatabaseStatsDto> getStats() {
        return ResponseEntity.ok(databaseAdminService.getStats());
    }

    /**
     * DELETE /api/database/spread-log — clear historical opportunities (not pairs/fees).
     */
    @DeleteMapping("/spread-log")
    public ResponseEntity<DatabaseFlushResultDto> flushSpreadLog() {
        return ResponseEntity.ok(databaseAdminService.flushSpreadLog());
    }
}
