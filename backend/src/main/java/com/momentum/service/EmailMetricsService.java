package com.momentum.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * In-memory holder for the most recent email send outcome, used to power the operational metrics
 * endpoint. Same pattern as {@link MetricsService} for the algorithm run: reflects live process
 * state (resets on restart), not a historical audit log.
 *
 * lastSentAt only ever moves forward on an actual successful send — a failure updates lastError
 * without touching it, so "when did email last actually work" and "what broke most recently" stay
 * independently readable instead of one overwriting the other.
 */
@Service
public class EmailMetricsService {

    private volatile LocalDateTime lastSentAt;
    private volatile String lastError;

    public synchronized void recordSuccess(LocalDateTime sentAt) {
        lastSentAt = sentAt;
        lastError = null;
    }

    public synchronized void recordFailure(String errorMessage) {
        lastError = errorMessage;
    }

    public LocalDateTime getLastSentAt() {
        return lastSentAt;
    }

    public String getLastError() {
        return lastError;
    }
}
