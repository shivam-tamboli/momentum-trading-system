package com.momentum.repository;

import com.momentum.model.IndexSwitchHistory;
import com.momentum.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndexSwitchHistoryRepository extends JpaRepository<IndexSwitchHistory, Long> {

    List<IndexSwitchHistory> findByUserOrderBySwitchedAtDesc(User user);
}
