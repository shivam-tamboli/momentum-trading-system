package com.momentum.controller;

import com.momentum.model.enums.ActionType;
import com.momentum.repository.RecommendationRepository;
import com.momentum.repository.StockRepository;
import com.momentum.repository.TradeRepository;
import com.momentum.service.MetricsService;
import com.momentum.service.MomentumAlgorithmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final MomentumAlgorithmService momentumAlgorithmService;
    private final MetricsService metricsService;
    private final StockRepository stockRepository;
    private final RecommendationRepository recommendationRepository;
    private final TradeRepository tradeRepository;

    public AdminController(MomentumAlgorithmService momentumAlgorithmService,
                            MetricsService metricsService,
                            StockRepository stockRepository,
                            RecommendationRepository recommendationRepository,
                            TradeRepository tradeRepository) {
        this.momentumAlgorithmService = momentumAlgorithmService;
        this.metricsService = metricsService;
        this.stockRepository = stockRepository;
        this.recommendationRepository = recommendationRepository;
        this.tradeRepository = tradeRepository;
    }

    @PostMapping("/run-algorithm")
    public ResponseEntity<String> runAlgorithm() {
        try {
            momentumAlgorithmService.generateWeeklyRecommendations();
            return ResponseEntity.ok("Algorithm completed successfully. Recommendations generated for all 4 indexes.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Algorithm failed: " + e.getMessage());
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<MetricsResponse> metrics() {
        String dbStatus = "UP";
        long stockCount = 0;
        long recommendationCount = 0;
        long totalTrades = 0;
        long buyCount = 0;
        long sellCount = 0;

        try {
            stockCount = stockRepository.count();
            recommendationCount = recommendationRepository.count();
            totalTrades = tradeRepository.count();
            buyCount = tradeRepository.countByAction(ActionType.BUY);
            sellCount = tradeRepository.countByAction(ActionType.SELL);
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
                new DatabaseStats(stockCount, recommendationCount)
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
