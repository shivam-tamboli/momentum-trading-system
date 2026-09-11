package com.momentum.service;

import com.momentum.model.DailyRecommendation;
import com.momentum.model.DailyTrade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import com.momentum.model.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * All outbound user notifications, over the existing spring.mail SMTP config (see
 * application.properties / MAIL_HOST etc). Every send method swallows its own exceptions and
 * only logs — a failed or misconfigured mail send must never fail the trading run, scoring run,
 * or index switch it's reporting on. See ARCHITECTURE.md for why this class didn't exist before
 * now: an earlier version was deleted during the daily-engine rewrite and never rebuilt.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public EmailService(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
    }

    // ---- Email 1: today's top 5 for the user's index, sent after Job 1 (scoring) ----

    public void sendTopFiveEmail(User user, String indexName, List<DailyRecommendation> top5, LocalDateTime scoredAtUtc) {
        String displayIndex = displayIndexName(indexName);
        String subject = "Today's Top 5 — " + displayIndex + " | " + dateLabel(scoredAtUtc);

        StringBuilder body = new StringBuilder();
        body.append("Today's Top 5 — ").append(displayIndex).append("\n\n");
        body.append("Scored at ").append(dualTimezone(scoredAtUtc)).append("\n\n");

        int rank = 1;
        for (DailyRecommendation rec : top5) {
            body.append(rank++).append(". ").append(rec.getSymbol())
                    .append(" (").append(rec.getName()).append(") — momentum score ")
                    .append(rec.getMomentumScore()).append("\n");
        }

        Instant marketOpenEt = marketOpenInstant(scoredAtUtc);
        body.append("\nTrading begins at market open (").append(dualTimezone(marketOpenEt)).append(").\n");

        send(user.getEmail(), subject, body.toString());
    }

    // ---- Email 2: portfolio rebalanced, sent per user after their Job 2 rebalance completes ----

    public void sendPortfolioRebalancedEmail(User user, List<DailyTrade> bought, List<DailyTrade> sold,
                                              BigDecimal portfolioValue, LocalDateTime tradedAtUtc) {
        String subject = "Portfolio Rebalanced | " + dateLabel(tradedAtUtc);

        StringBuilder body = new StringBuilder();
        body.append("Portfolio Rebalanced\n\n");
        body.append(dualTimezone(tradedAtUtc)).append("\n\n");

        body.append("Bought:\n");
        appendTradeLines(body, bought);
        body.append("\nSold:\n");
        appendTradeLines(body, sold);

        BigDecimal boughtTotal = sumFilledAmount(bought);
        BigDecimal soldTotal = sumFilledAmount(sold);
        BigDecimal netChange = soldTotal.subtract(boughtTotal);
        body.append("\nNet change: ").append(formatSignedMoney(netChange))
                .append(netChange.signum() >= 0 ? " (cash freed)" : " (net invested)").append("\n");
        body.append("Current portfolio value: ").append(formatMoney(portfolioValue)).append("\n");

        List<DailyTrade> notFilled = concatNotFilled(bought, sold);
        if (!notFilled.isEmpty()) {
            body.append("\nIMPORTANT — the following trades did not complete normally:\n");
            for (DailyTrade trade : notFilled) {
                body.append("  ").append(trade.getSymbol()).append(" (").append(trade.getAction()).append(") — ")
                        .append(trade.getStatus() == TradeStatus.PENDING
                                ? "still PENDING, will be resolved during tonight's reconciliation"
                                : "FAILED")
                        .append("\n");
            }
        }

        send(user.getEmail(), subject, body.toString());
    }

    // ---- Email 3: index switch confirmation, sent immediately on switch ----

    public void sendIndexSwitchEmail(User user, String previousIndex, String newIndex, BigDecimal investmentAmount,
                                      List<DailyTrade> sold, List<DailyTrade> bought, boolean marketWasOpen,
                                      LocalDateTime switchedAtUtc) {
        String subject = "Index Switch Confirmed | " + dateLabel(switchedAtUtc);

        StringBuilder body = new StringBuilder();
        body.append("Index Switch Confirmed\n\n");
        body.append(dualTimezone(switchedAtUtc)).append("\n\n");
        body.append("Switched from ").append(previousIndex == null ? "no index (first pick)" : displayIndexName(previousIndex))
                .append(" to ").append(displayIndexName(newIndex)).append("\n");
        body.append("Investment amount: ").append(formatMoney(investmentAmount)).append("\n\n");

        if (!marketWasOpen) {
            body.append("The market was closed at the time of this switch — selling and buying will happen "
                    + "automatically at the next scheduled trading run.\n");
        } else {
            body.append("Sold:\n");
            appendTradeLines(body, sold);
            body.append("\nBought:\n");
            appendTradeLines(body, bought);
        }

        send(user.getEmail(), subject, body.toString());
    }

    // ---- Email 4: critical trade failure, sent immediately when a trade's status becomes FAILED ----

    public void sendTradeFailedEmail(User user, String symbol, ActionType action, LocalDateTime failedAtUtc) {
        String subject = "Trade Failed — Action Required | " + dateLabel(failedAtUtc);

        StringBuilder body = new StringBuilder();
        body.append("Trade Failed — Action Required\n\n");
        body.append(dualTimezone(failedAtUtc)).append("\n\n");
        body.append(symbol).append(" — attempted ").append(action).append(" — FAILED\n\n");
        body.append("Please check your Alpaca account directly to confirm your actual holdings.\n");

        send(user.getEmail(), subject, body.toString());
    }

    // ---- shared plumbing ----

    private void appendTradeLines(StringBuilder body, List<DailyTrade> trades) {
        if (trades.isEmpty()) {
            body.append("  None\n");
            return;
        }
        for (DailyTrade trade : trades) {
            if (trade.getStatus() == TradeStatus.FILLED) {
                body.append("  ").append(trade.getSymbol()).append(" — ")
                        .append(trade.getQuantity()).append(" shares @ ")
                        .append(formatMoney(trade.getPricePerShare())).append(" = ")
                        .append(formatMoney(trade.getAmount())).append("\n");
            } else {
                body.append("  ").append(trade.getSymbol()).append(" — ").append(trade.getStatus()).append("\n");
            }
        }
    }

    private List<DailyTrade> concatNotFilled(List<DailyTrade> bought, List<DailyTrade> sold) {
        return Stream.concat(bought.stream(), sold.stream())
                .filter(t -> t.getStatus() != TradeStatus.FILLED)
                .toList();
    }

    private BigDecimal sumFilledAmount(List<DailyTrade> trades) {
        return trades.stream()
                .filter(t -> t.getStatus() == TradeStatus.FILLED && t.getAmount() != null)
                .map(DailyTrade::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String formatMoney(BigDecimal amount) {
        return amount == null ? "—" : "$" + amount.setScale(2, RoundingMode.HALF_UP);
    }

    private String formatSignedMoney(BigDecimal amount) {
        String sign = amount.signum() >= 0 ? "+" : "-";
        return sign + "$" + amount.abs().setScale(2, RoundingMode.HALF_UP);
    }

    private String displayIndexName(String indexName) {
        return DailyScoringService.FULL_MARKET.equals(indexName) ? "Full Market" : indexName;
    }

    // Backend LocalDateTime values are UTC wall-clock (the JVM runs in UTC) — same rule the
    // frontend's parseBackendTimestamp follows, just on the Java side.
    private Instant toInstant(LocalDateTime utcTime) {
        return utcTime.toInstant(ZoneOffset.UTC);
    }

    private String dualTimezone(LocalDateTime utcTime) {
        return dualTimezone(toInstant(utcTime));
    }

    private String dualTimezone(Instant instant) {
        String ist = TIME_FORMAT.format(instant.atZone(IST_ZONE));
        String et = TIME_FORMAT.format(instant.atZone(ET_ZONE));
        return ist + " IST (" + et + " ET)";
    }

    private String dateLabel(LocalDateTime utcTime) {
        return DATE_FORMAT.format(toInstant(utcTime).atZone(IST_ZONE));
    }

    // 9:30 AM on the ET calendar date matching scoredAtUtc — computed per-email rather than
    // hardcoded so DST (EDT vs EST) is handled by the real IANA tz database, not a fixed offset.
    private Instant marketOpenInstant(LocalDateTime scoredAtUtc) {
        LocalDate etDate = toInstant(scoredAtUtc).atZone(ET_ZONE).toLocalDate();
        return etDate.atTime(9, 30).atZone(ET_ZONE).toInstant();
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            javaMailSender.send(message);
            log.info("Sent email to {}: {}", to, subject);
        } catch (Exception e) {
            log.warn("Failed to send email to {} ({}): {}", to, subject, e.getMessage());
        }
    }
}
