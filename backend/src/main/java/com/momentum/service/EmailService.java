package com.momentum.service;

import com.momentum.exception.EmailSendException;
import com.momentum.model.DailyRecommendation;
import com.momentum.model.DailyTrade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import com.momentum.model.enums.EmailType;
import com.momentum.model.enums.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

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
import java.util.Map;
import java.util.stream.Stream;

/**
 * All outbound user notifications, sent over Resend's HTTPS API (see application.properties /
 * RESEND_API_KEY etc) rather than SMTP — Render's free tier blocks outbound SMTP (port 587),
 * which made every email silently hang forever with no timeout and no error (see
 * /admin/test-email's own incident history). A plain HTTPS POST has none of that: it goes over
 * port 443, which is never blocked the way SMTP ports are, and the RestTemplate below has an
 * explicit timeout so a network problem fails fast and loud instead of hanging a request thread
 * indefinitely.
 *
 * Every business-email method routes through send(), which swallows its own exceptions — a
 * failed or misconfigured mail send must never fail the trading run, scoring run, or index switch
 * it's reporting on. See ARCHITECTURE.md for why this class didn't exist before now: an earlier
 * version was deleted during the daily-engine rewrite and never rebuilt.
 *
 * A swallowed failure isn't a silent one, though: doSend() logs it at ERROR with the email type,
 * recipient, subject and full exception, and records it in {@link EmailMetricsService} so
 * /metrics can surface "is email actually working" without anyone digging through logs — see
 * sendTestEmail() and AdminController#testEmail for the on-demand way to check that directly.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final String RESEND_API_URL = "https://api.resend.com/emails";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final RestTemplate restTemplate;
    private final EmailMetricsService emailMetricsService;

    @Value("${resend.api-key}")
    private String resendApiKey;

    @Value("${resend.from-address}")
    private String fromAddress;

    public EmailService(EmailMetricsService emailMetricsService) {
        this.emailMetricsService = emailMetricsService;
        this.restTemplate = buildRestTemplate();
    }

    // Bounded connect/read timeouts, deliberately — a plain `new RestTemplate()` has none by
    // default, which is exactly the class of bug (an indefinite hang, not a clean error) that
    // motivated moving off SMTP in the first place. 10s read timeout comfortably covers a normal
    // Resend response while still failing fast if their API or the network is ever unreachable.
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
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

        send(EmailType.TOP_FIVE, user.getEmail(), subject, body.toString());
    }

    // ---- Email 2: portfolio rebalanced, sent per user after their Job 2 rebalance completes ----

    public void sendPortfolioRebalancedEmail(User user, List<DailyTrade> bought, List<DailyTrade> sold,
                                              BigDecimal portfolioValue, LocalDateTime tradedAtUtc,
                                              String skippedBuyReason) {
        String subject = "Portfolio Rebalanced | " + dateLabel(tradedAtUtc);

        StringBuilder body = new StringBuilder();
        body.append("Portfolio Rebalanced\n\n");
        body.append(dualTimezone(tradedAtUtc)).append("\n\n");

        body.append("Bought:\n");
        appendTradeLines(body, bought);
        body.append("\nSold:\n");
        appendTradeLines(body, sold);

        // Distinguishes "nothing new needed buying" from "wanted to buy but couldn't" — without
        // this, a sell that ran fine while its paired buy silently failed reads identically to a
        // rebalance that never needed to buy anything at all.
        if (skippedBuyReason != null) {
            body.append("\nNOTE — buying was skipped this run: ").append(skippedBuyReason).append("\n");
        }

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

        send(EmailType.PORTFOLIO_REBALANCED, user.getEmail(), subject, body.toString());
    }

    // ---- Email 3: index switch confirmation, sent immediately on switch ----

    public void sendIndexSwitchEmail(User user, String previousIndex, String newIndex, BigDecimal investmentAmount,
                                      List<DailyTrade> sold, List<DailyTrade> bought, boolean marketWasOpen,
                                      LocalDateTime switchedAtUtc, String skippedBuyReason) {
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

            if (skippedBuyReason != null) {
                body.append("\nNOTE — buying was skipped: ").append(skippedBuyReason).append("\n");
            }
        }

        send(EmailType.INDEX_SWITCH, user.getEmail(), subject, body.toString());
    }

    // ---- No rebalancing needed: holdings already match today's top 5, sent after Job 2 ----

    public void sendNoRebalancingNeededEmail(User user, String indexName, List<DailyRecommendation> holdings,
                                              BigDecimal portfolioValue, LocalDateTime asOfUtc) {
        String displayIndex = displayIndexName(indexName);
        String subject = "No Rebalancing Needed | " + dateLabel(asOfUtc);

        StringBuilder body = new StringBuilder();
        body.append("Your portfolio already holds today's top 5 ").append(displayIndex)
                .append(" stocks. No trades were placed.\n\n");
        body.append("Current holdings:\n");
        for (DailyRecommendation rec : holdings) {
            body.append("  ").append(rec.getSymbol()).append(" (").append(rec.getName()).append(")\n");
        }
        body.append("\nPortfolio value: ").append(formatMoney(portfolioValue)).append("\n");

        send(EmailType.NO_REBALANCING_NEEDED, user.getEmail(), subject, body.toString());
    }

    // ---- Trading window missed: market closed before Job 2 ever completed that day ----

    public void sendTradingWindowMissedEmail(User user, LocalDateTime asOfUtc) {
        String subject = "Trading Window Missed | " + dateLabel(asOfUtc);

        String body = "The market closed before today's rebalancing could execute. Your portfolio was not "
                + "changed. Trading will resume tomorrow at market open.\n";

        send(EmailType.TRADING_WINDOW_MISSED, user.getEmail(), subject, body);
    }

    // ---- Email 4: critical trade failure, sent immediately when a trade's status becomes FAILED ----

    public void sendTradeFailedEmail(User user, String symbol, ActionType action, LocalDateTime failedAtUtc) {
        String subject = "Trade Failed — Action Required | " + dateLabel(failedAtUtc);

        StringBuilder body = new StringBuilder();
        body.append("Trade Failed — Action Required\n\n");
        body.append(dualTimezone(failedAtUtc)).append("\n\n");
        body.append(symbol).append(" — attempted ").append(action).append(" — FAILED\n\n");
        body.append("Please check your Alpaca account directly to confirm your actual holdings.\n");

        send(EmailType.TRADE_FAILED, user.getEmail(), subject, body.toString());
    }

    // ---- Test email: on-demand SMTP health check, triggered via POST /admin/test-email ----
    // Unlike every business email above, this does NOT swallow failures — the entire point of
    // this endpoint is to hand the caller the exact SMTP error, so it calls doSend() directly and
    // lets EmailSendException propagate up to AdminController.
    public LocalDateTime sendTestEmail() {
        String subject = "Momentum Trading — Test Email";
        String body = "This is a test email sent via POST /admin/test-email to verify SMTP "
                + "delivery is working.\n\nIf you received this, email is healthy.";

        doSend(EmailType.TEST, fromAddress, subject, body);
        return LocalDateTime.now(ZoneOffset.UTC);
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

    // Used by every business-email method (sendTopFiveEmail, sendPortfolioRebalancedEmail, etc).
    // A failure here is expected to happen sometimes (bad address, SMTP hiccup, provider
    // throttling) and must never propagate — email is a notification layer, not part of the
    // trading/scoring flow it's reporting on. doSend() already logs and records the failure before
    // throwing, so there's nothing left to do here except stop it from going any further.
    private void send(EmailType type, String to, String subject, String body) {
        try {
            doSend(type, to, subject, body);
        } catch (EmailSendException e) {
            // Logged and recorded in doSend() already — swallowed here by design.
        }
    }

    // The one place that actually talks to Resend. Always logs the outcome and records it in
    // EmailMetricsService; throws EmailSendException on failure instead of swallowing, so the two
    // callers above can each decide what "never propagate" vs "surface the real error" means for
    // their situation.
    private void doSend(EmailType type, String to, String subject, String body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(resendApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of(
                    "from", fromAddress,
                    "to", List.of(to),
                    "subject", subject,
                    "text", body
            );

            restTemplate.postForEntity(RESEND_API_URL, new HttpEntity<>(payload, headers), String.class);

            LocalDateTime sentAt = LocalDateTime.now(ZoneOffset.UTC);
            emailMetricsService.recordSuccess(sentAt);
            log.info("Sent email [{}] to {}: {}", type, to, subject);
        } catch (Exception e) {
            String errorMessage = extractErrorMessage(e);
            emailMetricsService.recordFailure(type + ": " + errorMessage);
            log.error("Email send FAILED — type={}, recipient={}, subject={}, error={}",
                    type, to, subject, errorMessage, e);
            throw new EmailSendException(errorMessage, e);
        }
    }

    // Resend returns a JSON error body (e.g. {"statusCode":403,"message":"...","name":"..."}) on
    // a non-2xx response — that body is the actually useful, exact error (an invalid API key, an
    // unverified sender domain, a bad recipient), so it's preferred over the generic
    // HttpStatusCodeException message whenever Spring managed to capture one.
    private String extractErrorMessage(Exception e) {
        if (e instanceof HttpStatusCodeException httpError) {
            String responseBody = httpError.getResponseBodyAsString();
            return responseBody.isBlank() ? httpError.getMessage() : responseBody;
        }
        return e.getMessage();
    }
}
