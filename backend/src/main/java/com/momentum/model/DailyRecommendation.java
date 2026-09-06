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

    @Column(name = "momentum_score", nullable = false)
    private BigDecimal momentumScore;

    @CreationTimestamp
    @Column(name = "scored_at", updatable = false)
    private LocalDateTime scoredAt;
}
