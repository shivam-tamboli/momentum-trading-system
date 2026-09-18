package com.momentum.model.enums;

public enum Job2Status {
    // Written the instant rebalanceUser() starts working for a user, before any Alpaca call —
    // so a crash mid-run (JVM killed, Render restart) leaves a visible "started but never
    // finished" row instead of silence. Every other status below overwrites this once the
    // attempt actually reaches an outcome.
    IN_PROGRESS,
    COMPLETED,
    NO_REBALANCE_NEEDED,
    MARKET_CLOSED,
    FAILED,
    NOT_RUN
}
