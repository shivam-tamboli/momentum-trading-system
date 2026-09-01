package com.momentum.repository;

import com.momentum.model.Recommendation;
import com.momentum.model.Stock;
import com.momentum.model.enums.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByActionAndWeekDate(ActionType action, LocalDate weekDate);

    List<Recommendation> findByIndexNameAndWeekDate(String indexName, LocalDate weekDate);

    // Returns a List rather than Optional/single result: pre-existing duplicate rows from before
    // this upsert logic existed may still be present (and some may be referenced by historical
    // Trade rows via a foreign key, so they can't simply be deleted). Ordered by id so callers
    // deterministically pick the same "first" row to update on every run.
    List<Recommendation> findByStockAndIndexNameAndWeekDateOrderById(Stock stock, String indexName, LocalDate weekDate);

    @Query("""
            SELECT r FROM Recommendation r
            WHERE r.indexName = :indexName
              AND r.weekDate = :weekDate
              AND r.createdAt = (
                  SELECT MAX(r2.createdAt) FROM Recommendation r2
                  WHERE r2.stock = r.stock
                    AND r2.indexName = :indexName
                    AND r2.weekDate = :weekDate
              )
            """)
    List<Recommendation> findLatestByIndexNameAndWeekDate(
            @Param("indexName") String indexName,
            @Param("weekDate") LocalDate weekDate);
}
