package com.momentum.service;

import com.momentum.model.SchedulerState;
import com.momentum.repository.SchedulerStateRepository;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Drives the two daily engine jobs off Alpaca's own market clock — never a hardcoded time. Spring
 * can't express "3 hours before a time that changes every day" with a static cron expression,
 * so this polls the clock once a minute instead and fires each job exactly once per trading day:
 *
 * <ul>
 *   <li>Job 1 fires anywhere in the window from 3 hours before the next open up to the open
 *       itself (a window, not one exact minute, so it can't be missed by scheduling jitter).</li>
 *   <li>Job 2 fires on the first poll where the market is open, each day — but only if Job 1
 *       already completed successfully that same trading day. If Job 1 failed or hasn't run yet,
 *       Job 2 is skipped rather than trading against stale or missing recommendations.</li>
 * </ul>
 *
 * The three "already ran today" guards are persisted to {@code scheduler_state} (a single row,
 * see {@link SchedulerState}) every time they change, and reloaded on startup. Without this, a
 * backend restart between Job 1 succeeding and Job 2 firing would lose job1LastSuccessDate from
 * memory — Job 1's window has already closed by then so it won't retry, and Job 2 requires
 * job1LastSuccessDate to match today, so the entire trading day would silently never fire for
 * anyone.
 */
@Service
public class DailyEngineSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DailyEngineSchedulerService.class);
    private static final long JOB1_LEAD_HOURS = 3;
    private static final Long STATE_ID = 1L;

    private final AlpacaAPI systemAlpacaAPI;
    private final IndexConstituentService indexConstituentService;
    private final DailyScoringService dailyScoringService;
    private final DailyTradingService dailyTradingService;
    private final SchedulerStateRepository schedulerStateRepository;

    private volatile LocalDate job1LastRunDate;
    private volatile LocalDate job1LastSuccessDate;
    private volatile LocalDate job2LastRunDate;

    public DailyEngineSchedulerService(AlpacaAPI systemAlpacaAPI,
                                        IndexConstituentService indexConstituentService,
                                        DailyScoringService dailyScoringService,
                                        DailyTradingService dailyTradingService,
                                        SchedulerStateRepository schedulerStateRepository) {
        this.systemAlpacaAPI = systemAlpacaAPI;
        this.indexConstituentService = indexConstituentService;
        this.dailyScoringService = dailyScoringService;
        this.dailyTradingService = dailyTradingService;
        this.schedulerStateRepository = schedulerStateRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadStateOnStartup() {
        schedulerStateRepository.findById(STATE_ID).ifPresentOrElse(state -> {
            job1LastRunDate = state.getJob1LastRunDate();
            job1LastSuccessDate = state.getJob1LastSuccessDate();
            job2LastRunDate = state.getJob2LastRunDate();
            log.info("Scheduler: restored state from DB — job1LastRunDate={}, "
                            + "job1LastSuccessDate={}, job2LastRunDate={}",
                    job1LastRunDate, job1LastSuccessDate, job2LastRunDate);
        }, () -> log.info("Scheduler: no prior state row found — starting fresh"));
    }

    private void persistState() {
        SchedulerState state = schedulerStateRepository.findById(STATE_ID)
                .orElseGet(() -> new SchedulerState(STATE_ID, null, null, null));
        state.setJob1LastRunDate(job1LastRunDate);
        state.setJob1LastSuccessDate(job1LastSuccessDate);
        state.setJob2LastRunDate(job2LastRunDate);
        schedulerStateRepository.save(state);
    }

    @Scheduled(fixedRate = 60_000)
    public void pollClockAndDispatch() {
        Clock clock;
        try {
            clock = systemAlpacaAPI.clock().get();
        } catch (Exception e) {
            log.error("Scheduler: failed to fetch Alpaca market clock: {}", e.getMessage(), e);
            return;
        }

        ZonedDateTime now = clock.getTimestamp();
        ZonedDateTime nextOpen = clock.getNextOpen();
        boolean isOpen = Boolean.TRUE.equals(clock.getIsOpen());

        if (!isOpen) {
            maybeRunJob1(now, nextOpen);
        } else {
            maybeRunJob2(now);
        }
    }

    // Fires once, anywhere from 3 hours before the next open up to the open itself — a window
    // rather than one exact minute, so a missed or delayed poll can't skip the day entirely.
    private void maybeRunJob1(ZonedDateTime now, ZonedDateTime nextOpen) {
        LocalDate tradingDay = nextOpen.toLocalDate();
        if (tradingDay.equals(job1LastRunDate)) {
            return;
        }

        ZonedDateTime windowStart = nextOpen.minusHours(JOB1_LEAD_HOURS);
        if (now.isBefore(windowStart) || !now.isBefore(nextOpen)) {
            return;
        }

        job1LastRunDate = tradingDay;
        persistState();
        log.info("Scheduler: Job 1 window reached ({} until open at {}) — refreshing index constituents "
                + "and running daily scoring for {}", java.time.Duration.between(now, nextOpen), nextOpen, tradingDay);

        try {
            String refreshSummary = indexConstituentService.refresh();
            log.info("Scheduler: Job 1 index constituent refresh: {}", refreshSummary);
            dailyScoringService.runDailyScoring();
            job1LastSuccessDate = tradingDay;
            persistState();
            log.info("Scheduler: Job 1 complete for {}", tradingDay);
        } catch (Exception e) {
            log.error("Scheduler: Job 1 failed for {}: {}", tradingDay, e.getMessage(), e);
        }
    }

    // Fires once, on the first poll after the market is confirmed open each day — but only if
    // Job 1 already succeeded for this same trading day. If Job 1 never ran or threw, today's
    // recommendations are stale or absent, so trading is skipped rather than rebalancing against
    // bad data; it simply resumes on the next trading day.
    private void maybeRunJob2(ZonedDateTime now) {
        LocalDate tradingDay = now.toLocalDate();
        if (tradingDay.equals(job2LastRunDate)) {
            return;
        }

        if (!tradingDay.equals(job1LastSuccessDate)) {
            log.warn("Scheduler: Job 2 skipped for {} — Job 1 has not completed successfully today",
                    tradingDay);
            return;
        }

        job2LastRunDate = tradingDay;
        persistState();
        log.info("Scheduler: Job 2 — market open confirmed, running daily trading for {}", tradingDay);

        try {
            dailyTradingService.runDailyTrading();
            log.info("Scheduler: Job 2 complete for {}", tradingDay);
        } catch (Exception e) {
            log.error("Scheduler: Job 2 failed for {}: {}", tradingDay, e.getMessage(), e);
        }
    }
}
