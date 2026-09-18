package com.momentum.model;

import com.momentum.model.enums.Job1Status;
import com.momentum.model.enums.Job2Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per (user, calendar day) — the daily engine's audit trail, written regardless of
 * whether any trade actually happened that day. {@code daily_trade} only gets rows when a real
 * buy/sell order is placed, so a day where Job 2 correctly found nothing to do (holdings already
 * matched the top 5) or never got the chance to run (market closed) left zero trace anywhere —
 * indistinguishable, from the data alone, from a silent failure. This table is that missing trace.
 *
 * Job 1 creates the row for a given day (job1_status + top5_symbols, job2_status left NOT_RUN);
 * Job 2 updates the same row later that day once it runs — see
 * {@link com.momentum.service.DailyEngineLogService} for the upsert.
 */
@Entity
@Table(name = "daily_engine_log", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "log_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyEngineLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "job1_status", nullable = false)
    private Job1Status job1Status;

    @Enumerated(EnumType.STRING)
    @Column(name = "job2_status", nullable = false)
    private Job2Status job2Status;

    // Comma-separated, e.g. "AMD,CRWD,PANW,MRVL,FTNT" — the top 5 for this user's selected index
    // at the moment Job 1 ran that day. Nullable: not known yet if Job 1 hasn't run (NOT_RUN/FAILED).
    @Column(name = "top5_symbols", columnDefinition = "TEXT")
    private String top5Symbols;

    // Human-readable, e.g. "Holdings already match today's top 5. No trades placed." or
    // "Bought PANW, CRWD. Sold INTC, SNDK." — written once Job 2 actually reaches an outcome for
    // this user. Nullable until then.
    @Column(name = "rebalance_summary", columnDefinition = "TEXT")
    private String rebalanceSummary;

    // The user's portfolio value at the moment Job 2 finished, when it was fetchable. Same
    // best-effort nullability as EmailService's own use of this figure — a failed Alpaca fetch
    // shouldn't block the rest of the log row from being written.
    @Column(name = "portfolio_value")
    private BigDecimal portfolioValue;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
