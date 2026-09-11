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

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Drives the daily engine jobs off Alpaca's own market clock — never a hardcoded time. Spring
 * can't express "3 hours before a time that changes every day" with a static cron expression,
 * so this polls the clock once a minute instead:
 *
 * <ul>
 *   <li>Job 1 fires anywhere in the window from 3 hours before the next open up to the open
 *       itself (a window, not one exact minute, so it can't be missed by scheduling jitter).</li>
 *   <li>Job 2 retries every 15 minutes for as long as the market is open, until it succeeds —
 *       not a single fire-once shot. Each attempt only proceeds if Job 1 already completed
 *       successfully today; the "did Job 2 already run today" guard itself lives in
 *       {@link DailyTradingService#runDailyTradingIfNeeded()}, backed by {@code scheduler_state}
 *       directly, so it's correct regardless of whether the scheduler, the admin endpoint, or
 *       both end up calling it the same day.</li>
 *   <li>If the market closes having never seen Job 2 complete, that's logged clearly and every
 *       eligible user gets a "Trading Window Missed" email — once, not on every poll.</li>
 * </ul>
 *
 * job1LastRunDate/job1LastSuccessDate/job2MissedAlertDate are persisted to {@code scheduler_state}
 * (a single row, see {@link SchedulerState}) every time they change, and reloaded on startup.
 * Without this, a backend restart between Job 1 succeeding and Job 2 firing would lose
 * job1LastSuccessDate from memory — Job 1's window has already closed by then so it won't retry,
 * and Job 2 requires job1LastSuccessDate to match today, so the entire trading day would silently
 * never fire for anyone.
 */
@Service
public class DailyEngineSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DailyEngineSchedulerService.class);
    private static final long JOB1_LEAD_HOURS = 3;
    private static final long JOB2_RETRY_MINUTES = 15;
    private static final Long STATE_ID = 1L;

    private final AlpacaAPI systemAlpacaAPI;
    private final DailyScoringService dailyScoringService;
    private final DailyTradingService dailyTradingService;
    private final SchedulerStateRepository schedulerStateRepository;

    private volatile LocalDate job1LastRunDate;
    private volatile LocalDate job1LastSuccessDate;
    private volatile LocalDate job2MissedAlertDate;
    private volatile ZonedDateTime job2LastAttemptTime;

    public DailyEngineSchedulerService(AlpacaAPI systemAlpacaAPI,
                                        DailyScoringService dailyScoringService,
                                        DailyTradingService dailyTradingService,
                                        SchedulerStateRepository schedulerStateRepository) {
        this.systemAlpacaAPI = systemAlpacaAPI;
        this.dailyScoringService = dailyScoringService;
        this.dailyTradingService = dailyTradingService;
        this.schedulerStateRepository = schedulerStateRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadStateOnStartup() {
        schedulerStateRepository.findById(STATE_ID).ifPresentOrElse(state -> {
            job1LastRunDate = state.getJob1LastRunDate();
            job1LastSuccessDate = state.getJob1LastSuccessDate();
            job2MissedAlertDate = state.getJob2MissedAlertDate();
            log.info("Scheduler: restored state from DB — job1LastRunDate={}, "
                            + "job1LastSuccessDate={}, job2MissedAlertDate={}",
                    job1LastRunDate, job1LastSuccessDate, job2MissedAlertDate);
        }, () -> log.info("Scheduler: no prior state row found — starting fresh"));
    }

    // Owns job1LastRunDate/job1LastSuccessDate/job2MissedAlertDate only — job2_last_run_date is
    // DailyTradingService's own field, written directly by runDailyTradingIfNeeded(). Same
    // fetch-mutate-save pattern as every other writer on this shared row: fetch fresh, touch only
    // the fields this class owns, save the whole entity — so two writers on the same row can't
    // clobber each other's columns.
    private void persistState() {
        SchedulerState state = schedulerStateRepository.findById(STATE_ID)
                .orElseGet(() -> new SchedulerState(STATE_ID, null, null, null, null, null, null));
        state.setJob1LastRunDate(job1LastRunDate);
        state.setJob1LastSuccessDate(job1LastSuccessDate);
        state.setJob2MissedAlertDate(job2MissedAlertDate);
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
            maybeAlertJob2Missed(now, nextOpen);
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
        log.info("Scheduler: Job 1 window reached ({} until open at {}) — running daily scoring for {}",
                java.time.Duration.between(now, nextOpen), nextOpen, tradingDay);

        try {
            dailyScoringService.runDailyScoring();
            job1LastSuccessDate = tradingDay;
            persistState();
            log.info("Scheduler: Job 1 complete for {}", tradingDay);
        } catch (Exception e) {
            log.error("Scheduler: Job 1 failed for {}: {}", tradingDay, e.getMessage(), e);
        }
    }

    // Retries every JOB2_RETRY_MINUTES for as long as the market stays open, until
    // runDailyTradingIfNeeded() itself reports the day is done — this is what turns "Job 2 only
    // gets one shot at market open" into "Job 2 keeps trying until it actually succeeds or the
    // window closes." The 15-minute spacing is just a throttle on how often this method does
    // anything at all; runDailyTradingIfNeeded() is the real, DB-backed guard against running
    // twice, so a missed or slow throttle window can't cause a double-run.
    private void maybeRunJob2(ZonedDateTime now) {
        if (job2LastAttemptTime != null
                && Duration.between(job2LastAttemptTime, now).toMinutes() < JOB2_RETRY_MINUTES) {
            return;
        }
        job2LastAttemptTime = now;

        LocalDate tradingDay = now.toLocalDate();
        if (!tradingDay.equals(job1LastSuccessDate)) {
            log.warn("Scheduler: Job 2 attempt skipped for {} — Job 1 has not completed successfully "
                    + "today (will check again in {} min)", tradingDay, JOB2_RETRY_MINUTES);
            return;
        }

        log.info("Scheduler: Job 2 attempt for {} — market open, Job 1 succeeded", tradingDay);
        DailyTradingService.TradingRunOutcome outcome = dailyTradingService.runDailyTradingIfNeeded();
        log.info("Scheduler: Job 2 attempt outcome for {}: {}", tradingDay, outcome);
    }

    // Fires once, the first poll after the market has genuinely closed for the day (not the
    // pre-market "closed because it hasn't opened yet" state — distinguished by nextOpen pointing
    // to a later calendar date, meaning today's session already happened). If Job 2 never
    // completed today by then, that's a missed trading day: log it clearly and tell every
    // eligible user, once, not on every poll for the rest of the day.
    private void maybeAlertJob2Missed(ZonedDateTime now, ZonedDateTime nextOpen) {
        if (!nextOpen.toLocalDate().isAfter(now.toLocalDate())) {
            return;
        }

        LocalDate today = now.toLocalDate();
        if (today.equals(job2MissedAlertDate)) {
            return;
        }

        if (dailyTradingService.hasCompletedToday(today)) {
            return;
        }

        log.warn("Scheduler: market closed for {} and Job 2 never completed successfully — "
                + "notifying eligible users", today);
        dailyTradingService.notifyTradingWindowMissed();

        job2MissedAlertDate = today;
        persistState();
    }
}
