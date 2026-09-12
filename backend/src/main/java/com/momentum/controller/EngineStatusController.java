package com.momentum.controller;

import com.momentum.model.SchedulerState;
import com.momentum.repository.SchedulerStateRepository;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.clock.Clock;
import net.jacobpeterson.alpaca.rest.AlpacaClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Tells the frontend whether *today* is actually a trading day and whether today's Job 1 / Job 2
 * have completed — the one thing the dashboard previously had no way to know, forcing every
 * "Today"-labeled element to guess freshness from raw timestamps with calendar-day/elapsed-time
 * heuristics that read a stale Friday run as "live" all through Saturday morning. Plain
 * JWT-authenticated, same as /recommendations and /metrics — read-only status, not an admin action.
 */
@RestController
public class EngineStatusController {

    private static final Logger log = LoggerFactory.getLogger(EngineStatusController.class);
    private static final Long SCHEDULER_STATE_ID = 1L;

    private final AlpacaAPI systemAlpacaAPI;
    private final SchedulerStateRepository schedulerStateRepository;

    public EngineStatusController(AlpacaAPI systemAlpacaAPI, SchedulerStateRepository schedulerStateRepository) {
        this.systemAlpacaAPI = systemAlpacaAPI;
        this.schedulerStateRepository = schedulerStateRepository;
    }

    @GetMapping("/engine-status")
    public ResponseEntity<EngineStatusResponse> getStatus() {
        LocalDate today;
        boolean isTradingDay;
        try {
            Clock clock = systemAlpacaAPI.clock().get();
            today = clock.getTimestamp().toLocalDate();
            // An empty calendar result for [today, today] means today has no trading session at
            // all (weekend or market holiday) — the one distinction Alpaca's Clock endpoint alone
            // can't make, since its next_open field looks identical whether today already had a
            // session that closed, or never had one to begin with.
            isTradingDay = !systemAlpacaAPI.calendar().get(today, today).isEmpty();
        } catch (AlpacaClientException e) {
            log.error("Engine status: failed to fetch Alpaca clock/calendar: {}", e.getMessage(), e);
            return ResponseEntity.status(503).build();
        }

        SchedulerState state = schedulerStateRepository.findById(SCHEDULER_STATE_ID).orElse(null);
        LocalDate job1LastSuccessDate = state != null ? state.getJob1LastSuccessDate() : null;
        LocalDate job2LastRunDate = state != null ? state.getJob2LastRunDate() : null;

        return ResponseEntity.ok(new EngineStatusResponse(today, isTradingDay, job1LastSuccessDate, job2LastRunDate));
    }

    public record EngineStatusResponse(LocalDate today, boolean isTradingDay,
                                        LocalDate job1LastSuccessDate, LocalDate job2LastRunDate) {
    }
}
