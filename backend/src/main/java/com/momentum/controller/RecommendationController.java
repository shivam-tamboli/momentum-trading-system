package com.momentum.controller;

import com.momentum.repository.DailyRecommendationRepository;
import com.momentum.service.DailyScoringService;
import com.momentum.service.IndexConstituentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads today's top 5 from the daily engine ({@code daily_recommendation}, written by
 * {@link DailyScoringService}) — not the old weekly system's {@code recommendation} table, which
 * is frozen and unrelated to this now.
 */
@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

    private final DailyRecommendationRepository dailyRecommendationRepository;

    public RecommendationController(DailyRecommendationRepository dailyRecommendationRepository) {
        this.dailyRecommendationRepository = dailyRecommendationRepository;
    }

    @GetMapping("/snp500")
    public List<RecommendationResponse> getSnp500() {
        return getForFilter(IndexConstituentService.SP500);
    }

    @GetMapping("/sp400")
    public List<RecommendationResponse> getSp400() {
        return getForFilter(IndexConstituentService.SP400);
    }

    @GetMapping("/sp600")
    public List<RecommendationResponse> getSp600() {
        return getForFilter(IndexConstituentService.SP600);
    }

    @GetMapping("/nasdaq100")
    public List<RecommendationResponse> getNasdaq100() {
        return getForFilter(IndexConstituentService.NASDAQ100);
    }

    @GetMapping("/full-market")
    public List<RecommendationResponse> getFullMarket() {
        return getForFilter(DailyScoringService.FULL_MARKET);
    }

    private List<RecommendationResponse> getForFilter(String filterName) {
        return dailyRecommendationRepository.findByFilterNameOrderByMomentumScoreDesc(filterName).stream()
                .map(r -> new RecommendationResponse(r.getSymbol(), r.getName(), r.getMomentumScore(),
                        r.getScoredAt()))
                .collect(Collectors.toList());
    }

    // scoredAt lets the frontend tell fresh data from data kept by the safe-wipe guard after a
    // failed scoring run — never show stale recommendations as if they were computed today.
    public record RecommendationResponse(String symbol, String name, BigDecimal momentumScore,
                                          LocalDateTime scoredAt) {
    }
}
