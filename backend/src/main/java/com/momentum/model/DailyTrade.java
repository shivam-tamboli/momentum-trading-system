package com.momentum.model;

import com.momentum.model.enums.ActionType;
import com.momentum.model.enums.TradeStatus;
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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit log for the daily automated trading engine — deliberately separate from the old
 * {@code Trade} table, which requires FKs to {@code Stock}/{@code Recommendation} that the new
 * engine has no equivalent for (it never persists a stock catalog). Plain symbol string instead
 * of a Stock reference, same spirit as {@link DailyRecommendation}.
 */
@Entity
@Table(name = "daily_trade")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionType action;

    // FILLED: order confirmed filled, all fields below are real fill data.
    // PENDING: order was accepted by Alpaca but didn't confirm a fill within the wait window —
    //          fields below hold whatever was actually known at that point (never a fabricated
    //          $0 / 0-share "fill"). TradeReconciliationService resolves these to FILLED/FAILED
    //          once daily, once Alpaca's own record of the order reaches a real terminal state.
    // FAILED: either the order was never successfully placed at all, or it was placed but later
    //         canceled/expired/rejected — discovered via daily reconciliation, since a PENDING
    //         order isn't necessarily still open by the time anyone checks it again.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeStatus status;

    // Nullable: a PENDING or FAILED trade may not have every figure available yet — e.g. a
    // pending BUY's share count is genuinely unknown until the notional order fills, and a
    // FAILED order never got a price or an Alpaca order id at all.
    @Column
    private BigDecimal amount;

    @Column(name = "price_per_share")
    private BigDecimal pricePerShare;

    @Column
    private BigDecimal quantity;

    @Column(name = "alpaca_order_id")
    private String alpacaOrderId;

    @CreationTimestamp
    @Column(name = "traded_at", updatable = false)
    private LocalDateTime tradedAt;
}
