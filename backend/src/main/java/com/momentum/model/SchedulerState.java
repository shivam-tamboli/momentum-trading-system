package com.momentum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Single-row table holding the daily engine scheduler's "already ran today" guards. Without this,
 * a backend restart between Job 1 succeeding and Job 2 firing loses job1LastSuccessDate from
 * memory, and Job 2 never fires that day — a silently skipped trading day for every user. Always
 * one row, id fixed at 1.
 */
@Entity
@Table(name = "scheduler_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerState {

    @Id
    private Long id;

    @Column(name = "job1_last_run_date")
    private LocalDate job1LastRunDate;

    @Column(name = "job1_last_success_date")
    private LocalDate job1LastSuccessDate;

    @Column(name = "job2_last_run_date")
    private LocalDate job2LastRunDate;

    // Persisted so the /metrics fallback (MetricsController.buildAlgorithmStats) can show real
    // values instead of "—" after a restart wipes MetricsService's in-memory-only run stats —
    // these two are the process-run diagnostics from the most recent *successful* scoring run.
    @Column(name = "last_run_duration_ms")
    private Long lastRunDurationMs;

    @Column(name = "last_run_stocks_scored")
    private Integer lastRunStocksScored;

    // The date the "market closed before Job 2 ever ran" alert was last sent — so a missed
    // trading day only triggers one email per user, not one on every 60-second poll for the rest
    // of the day. Owned by DailyEngineSchedulerService; job1_last_run_date/job1_last_success_date
    // are also its fields, job_2_last_run_date is DailyTradingService's own (see that class).
    @Column(name = "job2_missed_alert_date")
    private LocalDate job2MissedAlertDate;
}
