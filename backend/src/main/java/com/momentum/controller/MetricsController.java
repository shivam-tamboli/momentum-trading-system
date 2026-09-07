package com.momentum.controller;

import com.momentum.model.enums.ActionType;
import com.momentum.repository.DailyRecommendationRepository;
import com.momentum.repository.DailyTradeRepository;
import com.momentum.service.IndexConstituentService;
import com.momentum.service.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

// Unlike /admin/**, this is a plain Supabase-JWT-authenticated endpoint (SecurityConfig's
// .anyRequest().authenticated() covers it, same as every other non-admin route) — read-only
// operational stats, not one of the genuinely dangerous admin actions. It used to live at
// /admin/metrics, but the frontend has no secure place to hold X-Admin-Key (anything shipped in
// client JS is readable by anyone via devtools), so that endpoint's one real caller — the
// dashboard's metrics page — was silently broken the moment /admin/** got locked down. Moving
// this here fixes the caller without weakening the key that guards actual order placement.
@RestController
public class MetricsController {

    private final MetricsService metricsService;
    private final DailyRecommendationRepository dailyRecommendationRepository;
    private final DailyTradeRepository dailyTradeRepository;
    private final IndexConstituentService indexConstituentService;

    public MetricsController(MetricsService metricsService,
                              DailyRecommendationRepository dailyRecommendationRepository,
                              DailyTradeRepository dailyTradeRepository,
                              IndexConstituentService indexConstituentService) {
        this.metricsService = metricsService;
        this.dailyRecommendationRepository = dailyRecommendationRepository;
        this.dailyTradeRepository = dailyTradeRepository;
        this.indexConstituentService = indexConstituentService;
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
