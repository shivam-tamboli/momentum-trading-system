package com.momentum.repository;

import com.momentum.model.BacktestResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestResultRepository extends JpaRepository<BacktestResult, Long> {

    List<BacktestResult> findByIndexNameOrderByResultDateAsc(String indexName);
}
