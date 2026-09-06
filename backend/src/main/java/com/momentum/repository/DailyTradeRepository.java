package com.momentum.repository;

import com.momentum.model.DailyTrade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyTradeRepository extends JpaRepository<DailyTrade, Long> {

    List<DailyTrade> findByUserOrderByTradedAtDesc(User user);

    long countByAction(ActionType action);
}
