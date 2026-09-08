package com.momentum.controller;

import com.momentum.service.DailyScoringService;
import com.momentum.service.DailyTradingService;
import com.momentum.service.TradeReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DailyScoringService dailyScoringService;
    private final DailyTradingService dailyTradingService;
    private final TradeReconciliationService tradeReconciliationService;

    public AdminController(DailyScoringService dailyScoringService,
                            DailyTradingService dailyTradingService,
                            TradeReconciliationService tradeReconciliationService) {
        this.dailyScoringService = dailyScoringService;
        this.dailyTradingService = dailyTradingService;
        this.tradeReconciliationService = tradeReconciliationService;
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
            dailyTradingService.runDailyTrading();
            return ResponseEntity.ok("Daily trading run completed.");
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
}
