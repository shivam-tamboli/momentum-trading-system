package com.momentum.repository;

import com.momentum.model.Recommendation;
import com.momentum.model.enums.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByActionAndWeekDate(ActionType action, LocalDate weekDate);

    List<Recommendation> findByIndexNameAndWeekDate(String indexName, LocalDate weekDate);
}
