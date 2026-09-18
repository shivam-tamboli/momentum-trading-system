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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DailyScoringService dailyScoringService;
    private final DailyTradingService dailyTradingService;
    private final TradeReconciliationService tradeReconciliationService;
    private final EmailService emailService;
    private final DailyEngineLogService dailyEngineLogService;
    private final UserRepository userRepository;

    public AdminController(DailyScoringService dailyScoringService,
                            DailyTradingService dailyTradingService,
                            TradeReconciliationService tradeReconciliationService,
                            EmailService emailService,
                            DailyEngineLogService dailyEngineLogService,
                            UserRepository userRepository) {
        this.dailyScoringService = dailyScoringService;
        this.dailyTradingService = dailyTradingService;
        this.tradeReconciliationService = tradeReconciliationService;
        this.emailService = emailService;
        this.dailyEngineLogService = dailyEngineLogService;
        this.userRepository = userRepository;
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

    // TEMPORARY — one-time manual backfill for the two days daily_engine_log couldn't have
    // covered itself, since the table didn't exist yet (Sep 16 and Sep 17, 2026). Values here are
    // exactly what was verified by hand: Sep 16's top 5 and no-rebalance outcome were directly
    // queried live that day; Sep 17's job2_status=NO_REBALANCE_NEEDED is the best-supported label
    // given zero daily_trade rows plus a confirmed Job 2 completion, but its top 5 is genuinely
    // unrecoverable (daily_recommendation is wiped every scoring run) — recorded as null, not
    // invented. Remove this endpoint once it's been called.
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

        return ResponseEntity.ok("Backfilled daily_engine_log for 2026-09-16 and 2026-09-17.");
    }
}
