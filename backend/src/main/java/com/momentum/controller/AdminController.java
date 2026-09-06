package com.momentum.controller;

import com.momentum.model.enums.ActionType;
import com.momentum.repository.DailyRecommendationRepository;
import com.momentum.repository.DailyTradeRepository;
import com.momentum.service.DailyScoringService;
import com.momentum.service.DailyTradingService;
import com.momentum.service.IndexConstituentService;
import com.momentum.service.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final MetricsService metricsService;
    private final DailyRecommendationRepository dailyRecommendationRepository;
    private final DailyTradeRepository dailyTradeRepository;
    private final IndexConstituentService indexConstituentService;
    private final DailyScoringService dailyScoringService;
    private final DailyTradingService dailyTradingService;

    public AdminController(MetricsService metricsService,
                            DailyRecommendationRepository dailyRecommendationRepository,
                            DailyTradeRepository dailyTradeRepository,
                            IndexConstituentService indexConstituentService,
                            DailyScoringService dailyScoringService,
                            DailyTradingService dailyTradingService) {
        this.metricsService = metricsService;
        this.dailyRecommendationRepository = dailyRecommendationRepository;
        this.dailyTradeRepository = dailyTradeRepository;
        this.indexConstituentService = indexConstituentService;
        this.dailyScoringService = dailyScoringService;
        this.dailyTradingService = dailyTradingService;
    }

    // Temporary manual triggers for development/testing of the daily engine, standing in for
    // the dynamic Alpaca-Clock-driven scheduler until that's wired in (see plan Stage 5).
    @PostMapping("/sync-index-constituents")
    public ResponseEntity<String> syncIndexConstituents() {
        try {
            String summary = indexConstituentService.refresh();
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Index constituent sync failed: " + e.getMessage());
        }
    }

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

    @GetMapping("/metrics")
    public ResponseEntity<MetricsResponse> metrics() {
        String dbStatus = "UP";
        long universeSize = 0;
        long recommendationCount = 0;
        long totalTrades = 0;
        long buyCount = 0;
        long sellCount = 0;

        try {
            // "Stock count" for the daily engine means the current tracked universe — the union of
            // all 4 index constituent lists — since this system never persists a stock catalog.
            Set<String> universe = new HashSet<>();
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.SP500));
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.NASDAQ100));
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.SP400));
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.SP600));
            universeSize = universe.size();

            recommendationCount = dailyRecommendationRepository.count();
            totalTrades = dailyTradeRepository.count();
            buyCount = dailyTradeRepository.countByAction(ActionType.BUY);
            sellCount = dailyTradeRepository.countByAction(ActionType.SELL);
        } catch (Exception e) {
            dbStatus = "DOWN";
        }

        MetricsResponse response = new MetricsResponse(
                new HealthStatus(dbStatus),
                new AlgorithmStats(
                        metricsService.getAlgorithmStatus().name(),
                        metricsService.getLastRunAt(),
                        metricsService.getLastRunDurationMs(),
                        metricsService.getLastRunStocksScored(),
                        metricsService.getLastRunError()
                ),
                new TradingStats(totalTrades, buyCount, sellCount),
                new DatabaseStats(universeSize, recommendationCount)
        );

        return ResponseEntity.ok(response);
    }

    public record HealthStatus(String status) {
    }

    public record AlgorithmStats(String status, LocalDateTime lastRunAt, Long durationMs, Integer stocksScored,
                                  String lastError) {
    }

    public record TradingStats(long totalTrades, long buyCount, long sellCount) {
    }

    public record DatabaseStats(long stockCount, long recommendationCount) {
    }

    public record MetricsResponse(HealthStatus health, AlgorithmStats algorithm, TradingStats trading,
                                   DatabaseStats database) {
    }
}
