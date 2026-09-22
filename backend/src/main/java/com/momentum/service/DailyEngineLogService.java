package com.momentum.service;

import com.momentum.model.DailyEngineLog;
import com.momentum.model.User;
import com.momentum.model.enums.Job1Status;
import com.momentum.model.enums.Job2Status;
import com.momentum.repository.DailyEngineLogRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * Upserts the one-row-per-(user, day) audit trail in {@code daily_engine_log}. Job 1 (system-wide,
 * same top 5 for every eligible user on a given day) normally creates the row via
 * {@link #recordJob1Result}; Job 2 (per-user) updates that same row later the same day via
 * {@link #recordJob2Result}. Either one can also be the row's creator — e.g. Job 2 recording
 * MARKET_CLOSED on a weekend, when Job 1 never ran at all that day and left no row to find.
 */
@Service
public class DailyEngineLogService {

    private final DailyEngineLogRepository dailyEngineLogRepository;

    public DailyEngineLogService(DailyEngineLogRepository dailyEngineLogRepository) {
        this.dailyEngineLogRepository = dailyEngineLogRepository;
    }

    public void recordJob1Result(User user, LocalDate logDate, Job1Status status, String top5SymbolsCsv) {
        saveWithRaceRetry(user, logDate, log -> {
            log.setJob1Status(status);
            log.setTop5Symbols(top5SymbolsCsv);
        });
    }

    public void recordJob2Result(User user, LocalDate logDate, Job2Status status, String rebalanceSummary,
                                  BigDecimal portfolioValue) {
        saveWithRaceRetry(user, logDate, log -> {
            log.setJob2Status(status);
            log.setRebalanceSummary(rebalanceSummary);
            log.setPortfolioValue(portfolioValue);
        });
    }

    // Guards against two near-simultaneous first writers for the same (user, day) — e.g. an
    // overlapping manual admin trigger and a scheduler retry — both missing findByUserAndLogDate
    // and both trying to insert a brand-new row. The unique constraint on (user_id, log_date)
    // means the loser's save() throws DataIntegrityViolationException instead of silently
    // duplicating; re-fetching finds the winner's already-committed row and applies this call's
    // update to that instead of losing the write entirely.
    private void saveWithRaceRetry(User user, LocalDate logDate, Consumer<DailyEngineLog> mutator) {
        DailyEngineLog log = findOrCreate(user, logDate);
        mutator.accept(log);
        try {
            dailyEngineLogRepository.save(log);
        } catch (DataIntegrityViolationException e) {
            DailyEngineLog existing = dailyEngineLogRepository.findByUserAndLogDate(user, logDate)
                    .orElseThrow(() -> e);
            mutator.accept(existing);
            dailyEngineLogRepository.save(existing);
        }
    }

    private DailyEngineLog findOrCreate(User user, LocalDate logDate) {
        return dailyEngineLogRepository.findByUserAndLogDate(user, logDate)
                .orElseGet(() -> new DailyEngineLog(null, user, logDate, Job1Status.NOT_RUN, Job2Status.NOT_RUN,
                        null, null, null, null));
    }
}
