package com.momentum.controller;

import com.momentum.model.enums.ActionType;
import com.momentum.repository.RecommendationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

    private final RecommendationRepository recommendationRepository;

    public RecommendationController(RecommendationRepository recommendationRepository) {
        this.recommendationRepository = recommendationRepository;
    }

    @GetMapping("/snp500")
    public List<RecommendationResponse> getSnp500() {
        return getRecommendationsForIndex("S&P 500");
    }

    @GetMapping("/snp400")
    public List<RecommendationResponse> getSnp400() {
        return getRecommendationsForIndex("S&P 400");
    }

    @GetMapping("/snp600")
    public List<RecommendationResponse> getSnp600() {
        return getRecommendationsForIndex("S&P 600");
    }

    @GetMapping("/nasdaq100")
    public List<RecommendationResponse> getNasdaq100() {
        return getRecommendationsForIndex("Nasdaq 100");
    }

    private List<RecommendationResponse> getRecommendationsForIndex(String indexName) {
        LocalDate weekDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        return recommendationRepository.findLatestByIndexNameAndWeekDate(indexName, weekDate).stream()
                .map(recommendation -> new RecommendationResponse(
                        recommendation.getStock().getSymbol(),
                        recommendation.getStock().getName(),
                        recommendation.getMomentumScore(),
                        recommendation.getAction(),
                        recommendation.getWeekDate()
                ))
                .collect(Collectors.toList());
    }

    public record RecommendationResponse(String symbol,
                                          String name,
                                          BigDecimal momentumScore,
                                          ActionType action,
                                          LocalDate weekDate) {
    }
}
