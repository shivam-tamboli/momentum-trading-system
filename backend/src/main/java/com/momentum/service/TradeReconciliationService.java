package com.momentum.service;

import com.momentum.config.AlpacaConfig;
import com.momentum.model.DailyTrade;
import com.momentum.model.User;
import com.momentum.model.enums.TradeStatus;
import com.momentum.repository.DailyTradeRepository;
import com.momentum.util.EncryptionUtil;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.orders.Order;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reconciles daily_trade rows stuck at PENDING against Alpaca's own order records.
 *
 * A PENDING row only ever means "we stopped watching before the order confirmed" —
 * {@link DailyTradingService}'s waitForFill gives up after ~24 seconds so a single slow order
 * can't block the whole trading run, but the order itself keeps executing on Alpaca's side
 * regardless of whether anything is still watching it. This runs once daily after market close,
 * by which point every order placed that day has had hours to reach a real terminal state.
 */
@Service
public class TradeReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TradeReconciliationService.class);

    // Terminal states that mean the order will never fill (any amount already filled before
    // cancellation would show up as a distinct PARTIALLY_FILLED case, handled separately below).
    private static final Set<OrderStatus> FAILED_STATUSES = EnumSet.of(
            OrderStatus.CANCELED, OrderStatus.EXPIRED, OrderStatus.REJECTED, OrderStatus.SUSPENDED);

    private final DailyTradeRepository dailyTradeRepository;
    private final AlpacaConfig alpacaConfig;
    private final EncryptionUtil encryptionUtil;
    private final EmailService emailService;

    public TradeReconciliationService(DailyTradeRepository dailyTradeRepository,
                                       AlpacaConfig alpacaConfig,
                                       EncryptionUtil encryptionUtil,
                                       EmailService emailService) {
        this.dailyTradeRepository = dailyTradeRepository;
        this.alpacaConfig = alpacaConfig;
        this.encryptionUtil = encryptionUtil;
        this.emailService = emailService;
    }

    // Fixed wall-clock time, unlike the daily engine's Job 1/Job 2 — reconciliation doesn't need
    // to track market open, it just needs to run well after close, so a plain cron expression is
    // enough here (no clock-polling window required).
    @Scheduled(cron = "0 0 21 * * MON-FRI", zone = "UTC")
    public void scheduledReconciliation() {
        log.info("Reconciliation: scheduled run starting");
        reconcilePendingTrades();
    }

    public void reconcilePendingTrades() {
        List<DailyTrade> pending = dailyTradeRepository.findByStatus(TradeStatus.PENDING);
        log.info("Reconciliation: {} trade(s) currently PENDING", pending.size());

        int filled = 0;
        int failed = 0;
        int stillPending = 0;
        int skipped = 0;

        for (DailyTrade trade : pending) {
            if (trade.getAlpacaOrderId() == null || trade.getAlpacaOrderId().isBlank()) {
                log.warn("Reconciliation: trade {} has no alpaca_order_id — nothing to check, skipping",
                        trade.getId());
                skipped++;
                continue;
            }

            try {
                AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(trade.getUser());
                Order order = userAlpacaAPI.orders().get(trade.getAlpacaOrderId(), false);

                if (order == null) {
                    log.warn("Reconciliation: order {} (trade {}) not found on Alpaca, leaving PENDING",
                            trade.getAlpacaOrderId(), trade.getId());
                    stillPending++;
                    continue;
                }

                OrderStatus status = order.getStatus();
                BigDecimal filledQty = parseOrNull(order.getFilledQuantity());
                boolean hasRealFill = filledQty != null && filledQty.compareTo(BigDecimal.ZERO) > 0;

                if (status == OrderStatus.FILLED || (status == OrderStatus.PARTIALLY_FILLED && hasRealFill)) {
                    BigDecimal price = parseOrNull(order.getAverageFillPrice());
                    if (price == null || filledQty == null) {
                        log.warn("Reconciliation: order {} (trade {}) reports {} but is missing fill "
                                        + "data, leaving PENDING",
                                trade.getAlpacaOrderId(), trade.getId(), status);
                        stillPending++;
                        continue;
                    }
                    trade.setStatus(TradeStatus.FILLED);
                    trade.setPricePerShare(price);
                    trade.setQuantity(filledQty);
                    trade.setAmount(price.multiply(filledQty));
                    dailyTradeRepository.save(trade);
                    filled++;
                } else if (FAILED_STATUSES.contains(status)) {
                    trade.setStatus(TradeStatus.FAILED);
                    dailyTradeRepository.save(trade);
                    emailService.sendTradeFailedEmail(trade.getUser(), trade.getSymbol(), trade.getAction(),
                            LocalDateTime.now(ZoneOffset.UTC));
                    failed++;
                } else {
                    // Genuinely still open (new/accepted/pending_new/etc.) — rare this long after
                    // close, but real. Leave it PENDING rather than guess at an outcome.
                    log.info("Reconciliation: order {} (trade {}) still open with status {}, leaving PENDING",
                            trade.getAlpacaOrderId(), trade.getId(), status);
                    stillPending++;
                }
            } catch (Exception e) {
                log.error("Reconciliation: failed to check order {} (trade {}): {}",
                        trade.getAlpacaOrderId(), trade.getId(), e.getMessage(), e);
                stillPending++;
            }
        }

        log.info("Reconciliation complete: {} filled, {} failed, {} still pending, {} skipped (no order id)",
                filled, failed, stillPending, skipped);
    }

    private BigDecimal parseOrNull(String value) {
        return (value != null && !value.isEmpty()) ? new BigDecimal(value) : null;
    }

    private AlpacaAPI buildUserAlpacaAPI(User user) {
        String apiKey = encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted());
        String apiSecret = encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted());
        return alpacaConfig.createUserAlpacaAPI(apiKey, apiSecret);
    }
}
