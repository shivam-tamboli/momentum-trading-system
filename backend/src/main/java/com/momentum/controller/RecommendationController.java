package com.momentum.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
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
                        r.getScoredAt(), r.getRet6m(), r.getRet3m(), r.getRet1m(), r.getVol3m()))
                .collect(Collectors.toList());
    }

    // scoredAt lets the frontend tell fresh data from data kept by the safe-wipe guard after a
    // failed scoring run — never show stale recommendations as if they were computed today.
    // ret6m/ret3m/ret1m/vol3m are nullable — rows written before that column existed have none,
    // though daily_recommendation is wiped and rewritten every scoring run so that's short-lived.
    //
    // Explicit @JsonProperty on the 4 return fields: Spring's SNAKE_CASE naming strategy inserts
    // an underscore before each uppercase letter in the Java name, but "ret6m"/"vol3m" have no
    // uppercase letter for it to find — there's no case boundary between a letter and a digit —
    // so without this override the strategy passes the name through unchanged as "ret6m" instead
    // of "ret_6m", silently breaking the frontend's snake_case contract.
    public record RecommendationResponse(String symbol, String name, BigDecimal momentumScore,
                                          LocalDateTime scoredAt,
                                          @JsonProperty("ret_6m") BigDecimal ret6m,
                                          @JsonProperty("ret_3m") BigDecimal ret3m,
                                          @JsonProperty("ret_1m") BigDecimal ret1m,
                                          @JsonProperty("vol_3m") BigDecimal vol3m) {
    }
}
