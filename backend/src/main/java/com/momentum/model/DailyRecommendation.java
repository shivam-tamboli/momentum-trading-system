package com.momentum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Only today's top 5 per filter ever live here — full scores are never persisted (computed and
 * ranked in memory by DailyScoringService, only the winners get written). Wiped and rewritten
 * every run, so this table never grows.
 */
@Entity
@Table(name = "daily_recommendation")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "S&P 500" / "NASDAQ 100" / "FULL_MARKET"
    @Column(name = "filter_name", nullable = false)
    private String filterName;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String name;

    // precision/scale must match DailyScoringService's calculateMomentumScore(), which computes to
    // 6 decimal places (.setScale(6, ...)) — without an explicit scale here, Hibernate's schema
    // generator defaults BigDecimal columns to scale 2, silently truncating every stored score to
    // cents-level precision even though the algorithm computed something far more precise.
    @Column(name = "momentum_score", nullable = false, precision = 10, scale = 6)
    private BigDecimal momentumScore;

    // The 4 inputs that produced momentumScore — DailyScoringService always computes these, but
    // only started persisting them once the frontend needed to show a real breakdown instead of
    // just the combined number. Nullable: rows written before this existed have no way to know
    // these retroactively, but daily_recommendation is wiped and rewritten every scoring run, so
    // that gap closes itself by the next run rather than lingering like daily_trade's history did.
    @Column(name = "ret_6m", precision = 12, scale = 6)
    private BigDecimal ret6m;

    @Column(name = "ret_3m", precision = 12, scale = 6)
    private BigDecimal ret3m;

    @Column(name = "ret_1m", precision = 12, scale = 6)
    private BigDecimal ret1m;

    @Column(name = "vol_3m", precision = 12, scale = 6)
    private BigDecimal vol3m;

    @CreationTimestamp
    @Column(name = "scored_at", updatable = false)
    private LocalDateTime scoredAt;
}
