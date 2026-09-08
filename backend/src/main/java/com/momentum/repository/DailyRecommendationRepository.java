package com.momentum.repository;

import com.momentum.model.DailyRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DailyRecommendationRepository extends JpaRepository<DailyRecommendation, Long> {

    List<DailyRecommendation> findByFilterNameOrderByMomentumScoreDesc(String filterName);

    Optional<DailyRecommendation> findTopByOrderByScoredAtDesc();
}
