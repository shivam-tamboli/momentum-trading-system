package com.momentum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per (index, trading day), written by {@code scripts/backtest.py} — not by the Spring
 * Boot app itself. This entity exists so Hibernate's {@code ddl-auto=update} creates the table
 * with the right schema; the Python script (run daily via {@code backtest.yml}) is the only thing
 * that ever writes to it, connecting to the same Postgres database directly.
 *
 * {@code portfolioValue}/{@code benchmarkValue} are a normalized cumulative-return index, both
 * starting at 100 on the first day of the backfilled range — not a dollar amount for any specific
 * investment. The frontend scales these by whatever starting amount the user enters; no dollar
 * figure is ever computed or stored here, only the growth curve itself.
 */
@Entity
@Table(name = "backtest_result", uniqueConstraints = @UniqueConstraint(columnNames = {"index_name", "result_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "S&P 500" / "S&P 400" / "S&P 600" / "NASDAQ 100" / "FULL_MARKET" — same filter-name values
    // DailyRecommendation and the live scoring engine use.
    @Column(name = "index_name", nullable = false)
    private String indexName;

    @Column(name = "result_date", nullable = false)
    private LocalDate resultDate;

    @Column(name = "portfolio_value", nullable = false, precision = 16, scale = 6)
    private BigDecimal portfolioValue;

    @Column(name = "benchmark_value", nullable = false, precision = 16, scale = 6)
    private BigDecimal benchmarkValue;

    // Comma-separated, that day's simulated top 5 for this index — same format as
    // DailyEngineLog.top5Symbols.
    @Column(name = "top5_symbols", columnDefinition = "TEXT")
    private String top5Symbols;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
