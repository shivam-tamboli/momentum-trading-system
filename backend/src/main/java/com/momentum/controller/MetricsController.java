package com.momentum.controller;

import com.momentum.model.DailyRecommendation;
import com.momentum.model.SchedulerState;
import com.momentum.model.enums.ActionType;
import com.momentum.model.enums.AlgorithmRunStatus;
import com.momentum.repository.DailyRecommendationRepository;
import com.momentum.repository.DailyTradeRepository;
import com.momentum.repository.SchedulerStateRepository;
import com.momentum.service.IndexConstituentService;
import com.momentum.service.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
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

    private static final Long SCHEDULER_STATE_ID = 1L;

    private final MetricsService metricsService;
    private final DailyRecommendationRepository dailyRecommendationRepository;
    private final DailyTradeRepository dailyTradeRepository;
    private final IndexConstituentService indexConstituentService;
    private final SchedulerStateRepository schedulerStateRepository;

    public MetricsController(MetricsService metricsService,
                              DailyRecommendationRepository dailyRecommendationRepository,
                              DailyTradeRepository dailyTradeRepository,
                              IndexConstituentService indexConstituentService,
                              SchedulerStateRepository schedulerStateRepository) {
        this.metricsService = metricsService;
        this.dailyRecommendationRepository = dailyRecommendationRepository;
        this.dailyTradeRepository = dailyTradeRepository;
        this.indexConstituentService = indexConstituentService;
        this.schedulerStateRepository = schedulerStateRepository;
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
                buildAlgorithmStats(),
                new TradingStats(totalTrades, buyCount, sellCount),
                new DatabaseStats(universeSize, recommendationCount)
        );

        return ResponseEntity.ok(response);
    }

    // MetricsService is in-memory only — a live process's own view of "did I just run this?" is
    // real and worth trusting when it has one. But Render restarts freely on the free tier (and
    // more often than the keep-alive job can reliably prevent — see the daily-trading-cron and
    // keep-alive workflows), and every restart wipes that state back to NEVER_RUN even though a
    // previous process instance genuinely scored successfully. When this instance has nothing to
    // report, fall back to what's actually persisted: the most recent daily_recommendation row's
    // scored_at, plus duration_ms/stocks_scored from scheduler_state (DailyScoringService writes
    // both there on every successful run specifically so this fallback never has to show "—").
    private AlgorithmStats buildAlgorithmStats() {
        AlgorithmRunStatus liveStatus = metricsService.getAlgorithmStatus();

        if (liveStatus != AlgorithmRunStatus.NEVER_RUN) {
            return new AlgorithmStats(
                    liveStatus.name(),
                    metricsService.getLastRunAt(),
                    metricsService.getLastRunDurationMs(),
                    metricsService.getLastRunStocksScored(),
                    metricsService.getLastRunError()
            );
        }

        Optional<DailyRecommendation> mostRecent = dailyRecommendationRepository.findTopByOrderByScoredAtDesc();
        if (mostRecent.isEmpty()) {
            return new AlgorithmStats(liveStatus.name(), null, null, null, null);
        }

        Optional<SchedulerState> schedulerState = schedulerStateRepository.findById(SCHEDULER_STATE_ID);

        return new AlgorithmStats(
                AlgorithmRunStatus.SUCCESS.name(),
                mostRecent.get().getScoredAt(),
                schedulerState.map(SchedulerState::getLastRunDurationMs).orElse(null),
                schedulerState.map(SchedulerState::getLastRunStocksScored).orElse(null),
                null
        );
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
