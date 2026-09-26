package com.momentum.controller;

import com.momentum.model.BacktestResult;
import com.momentum.repository.BacktestResultRepository;
import com.momentum.service.DailyScoringService;
import com.momentum.service.IndexConstituentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads the walk-forward momentum-vs-benchmark simulation written by {@code scripts/backtest.py}
 * (see {@code backtest_result}) — this controller never computes or writes a backtest itself,
 * only serves whatever the daily job already stored. Same URL naming as
 * {@link RecommendationController} for consistency.
 */
@RestController
@RequestMapping("/backtest")
public class BacktestController {

    // Deliberately its own map, separate from IndexConstituentService.INDEX_TO_ETF (which has no
    // FULL_MARKET entry). This backtest feature covers all 5 tracked options, including Full
    // Market; S&P 600 uses SPSM, same ETF as the live benchmark-comparison feature elsewhere.
    private static final Map<String, String> BACKTEST_ETF = Map.of(
            IndexConstituentService.SP500, "SPY",
            IndexConstituentService.SP400, "MDY",
            IndexConstituentService.SP600, "SPSM",
            IndexConstituentService.NASDAQ100, "QQQ",
            DailyScoringService.FULL_MARKET, "VTI"
    );

    private final BacktestResultRepository backtestResultRepository;

    public BacktestController(BacktestResultRepository backtestResultRepository) {
        this.backtestResultRepository = backtestResultRepository;
    }

    @GetMapping("/snp500")
    public BacktestResponse getSnp500() {
        return getForIndex(IndexConstituentService.SP500);
    }

    @GetMapping("/sp400")
    public BacktestResponse getSp400() {
        return getForIndex(IndexConstituentService.SP400);
    }

    @GetMapping("/sp600")
    public BacktestResponse getSp600() {
        return getForIndex(IndexConstituentService.SP600);
    }

    @GetMapping("/nasdaq100")
    public BacktestResponse getNasdaq100() {
        return getForIndex(IndexConstituentService.NASDAQ100);
    }

    @GetMapping("/full-market")
    public BacktestResponse getFullMarket() {
        return getForIndex(DailyScoringService.FULL_MARKET);
    }

    private BacktestResponse getForIndex(String indexName) {
        List<BacktestPoint> points = backtestResultRepository.findByIndexNameOrderByResultDateAsc(indexName).stream()
                .map(r -> new BacktestPoint(r.getResultDate(), r.getPortfolioValue(), r.getBenchmarkValue(),
                        r.getTop5Symbols()))
                .collect(Collectors.toList());

        return new BacktestResponse(indexName, BACKTEST_ETF.get(indexName), points);
    }

    public record BacktestPoint(LocalDate date, BigDecimal portfolioValue, BigDecimal benchmarkValue,
                                 String top5Symbols) {
    }

    public record BacktestResponse(String indexName, String etfSymbol, List<BacktestPoint> points) {
    }
}
