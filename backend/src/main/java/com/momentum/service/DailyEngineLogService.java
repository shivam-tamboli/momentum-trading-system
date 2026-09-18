package com.momentum.service;

import com.momentum.model.DailyEngineLog;
import com.momentum.model.User;
import com.momentum.model.enums.Job1Status;
import com.momentum.model.enums.Job2Status;
import com.momentum.repository.DailyEngineLogRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

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
        DailyEngineLog log = findOrCreate(user, logDate);
        log.setJob1Status(status);
        log.setTop5Symbols(top5SymbolsCsv);
        dailyEngineLogRepository.save(log);
    }

    public void recordJob2Result(User user, LocalDate logDate, Job2Status status, String rebalanceSummary,
                                  BigDecimal portfolioValue) {
        DailyEngineLog log = findOrCreate(user, logDate);
        log.setJob2Status(status);
        log.setRebalanceSummary(rebalanceSummary);
        log.setPortfolioValue(portfolioValue);
        dailyEngineLogRepository.save(log);
    }

    private DailyEngineLog findOrCreate(User user, LocalDate logDate) {
        return dailyEngineLogRepository.findByUserAndLogDate(user, logDate)
                .orElseGet(() -> new DailyEngineLog(null, user, logDate, Job1Status.NOT_RUN, Job2Status.NOT_RUN,
                        null, null, null, null));
    }
}
