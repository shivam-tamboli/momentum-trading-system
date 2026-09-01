package com.momentum.service;

import com.momentum.model.enums.AlgorithmRunStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * In-memory holder for the most recent algorithm run's outcome, used to power the
 * operational metrics endpoint. Intentionally not persisted: this reflects live
 * process state (resets on restart), not a historical audit log.
 */
@Service
public class MetricsService {

    private volatile AlgorithmRunStatus algorithmStatus = AlgorithmRunStatus.NEVER_RUN;
    private volatile LocalDateTime lastRunAt;
    private volatile Long lastRunDurationMs;
    private volatile Integer lastRunStocksScored;
    private volatile String lastRunError;

    public synchronized void recordRunStart() {
        algorithmStatus = AlgorithmRunStatus.RUNNING;
    }

    public synchronized void recordRunSuccess(int stocksScored, long durationMs) {
        algorithmStatus = AlgorithmRunStatus.SUCCESS;
        lastRunAt = LocalDateTime.now();
        lastRunDurationMs = durationMs;
        lastRunStocksScored = stocksScored;
        lastRunError = null;
    }

    public synchronized void recordRunFailure(String errorMessage, long durationMs) {
        algorithmStatus = AlgorithmRunStatus.FAILED;
        lastRunAt = LocalDateTime.now();
        lastRunDurationMs = durationMs;
        lastRunError = errorMessage;
    }

    public AlgorithmRunStatus getAlgorithmStatus() {
        return algorithmStatus;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public Long getLastRunDurationMs() {
        return lastRunDurationMs;
    }

    public Integer getLastRunStocksScored() {
        return lastRunStocksScored;
    }

    public String getLastRunError() {
        return lastRunError;
    }
}
