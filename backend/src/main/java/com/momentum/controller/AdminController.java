package com.momentum.controller;

import com.momentum.model.User;
import com.momentum.model.enums.Job1Status;
import com.momentum.model.enums.Job2Status;
import com.momentum.repository.UserRepository;
import com.momentum.service.DailyEngineLogService;
import com.momentum.service.DailyScoringService;
import com.momentum.service.DailyTradingService;
import com.momentum.service.EmailService;
import com.momentum.service.TradeReconciliationService;
import com.momentum.util.EncryptionUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DailyScoringService dailyScoringService;
    private final DailyTradingService dailyTradingService;
    private final TradeReconciliationService tradeReconciliationService;
    private final EmailService emailService;
    private final DailyEngineLogService dailyEngineLogService;
    private final UserRepository userRepository;
    private final EncryptionUtil encryptionUtil;

    public AdminController(DailyScoringService dailyScoringService,
                            DailyTradingService dailyTradingService,
                            TradeReconciliationService tradeReconciliationService,
                            EmailService emailService,
                            DailyEngineLogService dailyEngineLogService,
                            UserRepository userRepository,
                            EncryptionUtil encryptionUtil) {
        this.dailyScoringService = dailyScoringService;
        this.dailyTradingService = dailyTradingService;
        this.tradeReconciliationService = tradeReconciliationService;
        this.emailService = emailService;
        this.dailyEngineLogService = dailyEngineLogService;
        this.userRepository = userRepository;
        this.encryptionUtil = encryptionUtil;
    }

    // Manual triggers for development/testing of the daily engine — the scheduler and the
    // external cron backup (.github/workflows/daily-trading-cron.yml) fire these automatically
    // each trading day. Real order placement lives behind these two routes specifically, which is
    // why they stay gated by X-Admin-Key rather than moving to plain JWT auth like /metrics did.
    @PostMapping("/run-daily-scoring")
    public ResponseEntity<String> runDailyScoring() {
        try {
            dailyScoringService.runDailyScoring();
            return ResponseEntity.ok("Daily scoring completed successfully.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Daily scoring failed: " + e.getMessage());
        }
    }

    @PostMapping("/run-daily-trading")
    public ResponseEntity<String> runDailyTrading() {
        try {
            DailyTradingService.TradingRunOutcome outcome = dailyTradingService.runDailyTradingIfNeeded();
            return ResponseEntity.ok("Daily trading run: " + outcome);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Daily trading failed: " + e.getMessage());
        }
    }

    @PostMapping("/reconcile-pending-trades")
    public ResponseEntity<String> reconcilePendingTrades() {
        try {
            tradeReconciliationService.reconcilePendingTrades();
            return ResponseEntity.ok("Trade reconciliation completed.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Trade reconciliation failed: " + e.getMessage());
        }
    }

    // On-demand SMTP health check — lets anyone with the admin key confirm email is actually
    // deliverable right now, without waiting for a real trade/scoring event to trigger one.
    // Unlike every other email in the system, sendTestEmail() does not swallow its own failure —
    // the whole point here is to hand the caller the exact SMTP error.
    @PostMapping("/test-email")
    public ResponseEntity<TestEmailResponse> testEmail() {
        try {
            LocalDateTime sentAt = emailService.sendTestEmail();
            return ResponseEntity.ok(new TestEmailResponse(true, sentAt, null));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(new TestEmailResponse(false, null, e.getMessage()));
        }
    }

    public record TestEmailResponse(boolean success, LocalDateTime sentAt, String error) {
    }

    // TEMPORARY — one-time manual backfill for days daily_engine_log couldn't have covered
    // itself, since the table (and later, the recording code) didn't exist yet. recordJob1Result/
    // recordJob2Result both upsert by (user, log_date), so re-calling this for a day already
    // backfilled is harmless. Remove this endpoint once it's been called for every day it needs
    // to cover.
    //
    // Sep 16, 2026: verified live that day — top 5 and no-rebalance outcome directly queried.
    // Sep 17, 2026: job2_status=NO_REBALANCE_NEEDED is the best-supported label given zero
    //   daily_trade rows plus a confirmed Job 2 completion, but its top 5 is genuinely
    //   unrecoverable (daily_recommendation is wiped every scoring run) — recorded as null, not
    //   invented.
    // Sep 18, 2026: real trades exist in daily_trade (SELL FTNT @ 13:30:24 UTC, BUY INTC @
    //   13:30:28 UTC, both FILLED) — job1_status/job2_status=COMPLETED and the summary are drawn
    //   directly from those two rows. top5_symbols is the NASDAQ 100 top 5 confirmed in
    //   daily_recommendation (AMD, MRVL, CRWD, INTC, PANW), which is internally consistent with
    //   the trade (sold the one symbol not in that set, bought the one newly in it). This day's
    //   run predates the daily_engine_log feature itself (merged ~9.5 hours after this trade ran),
    //   which is why it has no row of its own despite Job 2 actually completing normally.
    @PostMapping("/backfill-engine-log")
    public ResponseEntity<String> backfillEngineLog() {
        User user = userRepository.findById(7L)
                .orElseThrow(() -> new IllegalStateException("User 7 not found"));

        dailyEngineLogService.recordJob1Result(user, LocalDate.of(2026, 9, 16), Job1Status.COMPLETED,
                "AMD,CRWD,PANW,MRVL,FTNT");
        dailyEngineLogService.recordJob2Result(user, LocalDate.of(2026, 9, 16), Job2Status.NO_REBALANCE_NEEDED,
                "Holdings already matched today's top 5. No trades placed.", null);

        dailyEngineLogService.recordJob1Result(user, LocalDate.of(2026, 9, 17), Job1Status.COMPLETED, null);
        dailyEngineLogService.recordJob2Result(user, LocalDate.of(2026, 9, 17), Job2Status.NO_REBALANCE_NEEDED,
                "System ran but top 5 data is not recoverable. Zero trades placed.", null);

        dailyEngineLogService.recordJob1Result(user, LocalDate.of(2026, 9, 18), Job1Status.COMPLETED,
                "AMD,MRVL,CRWD,INTC,PANW");
        dailyEngineLogService.recordJob2Result(user, LocalDate.of(2026, 9, 18), Job2Status.COMPLETED,
                "Bought INTC. Sold FTNT.", null);

        return ResponseEntity.ok("Backfilled daily_engine_log for 2026-09-16, 2026-09-17, and 2026-09-18.");
    }

    // TEMPORARY — one-time migration for rows written back when EncryptionUtil was a pass-through
    // no-op (see that class's javadoc). For every user, decrypt-then-re-encrypt their Alpaca
    // key/secret: a legacy plaintext value (no "enc:v1:" prefix) comes back from decrypt()
    // unchanged and gets genuinely encrypted for the first time; a value that's already encrypted
    // decrypts correctly and gets re-encrypted with a fresh random IV — a no-op in effect, safe to
    // call as many times as needed. Remove this endpoint once every existing user has been
    // migrated (check: no row's alpaca_api_key_encrypted/alpaca_api_secret_encrypted lacks the
    // "enc:v1:" prefix).
    @PostMapping("/migrate-encryption")
    public ResponseEntity<String> migrateEncryption() {
        List<User> users = userRepository.findAll();
        List<Long> migratedUserIds = new ArrayList<>();

        for (User user : users) {
            boolean changed = false;

            if (user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank()) {
                user.setAlpacaApiKeyEncrypted(encryptionUtil.encrypt(encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted())));
                changed = true;
            }
            if (user.getAlpacaApiSecretEncrypted() != null && !user.getAlpacaApiSecretEncrypted().isBlank()) {
                user.setAlpacaApiSecretEncrypted(encryptionUtil.encrypt(encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted())));
                changed = true;
            }

            if (changed) {
                userRepository.save(user);
                migratedUserIds.add(user.getId());
            }
        }

        return ResponseEntity.ok("Migrated encryption for user ids: " + migratedUserIds);
    }
}
