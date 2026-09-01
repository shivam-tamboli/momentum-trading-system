package com.momentum.repository;

import com.momentum.model.Trade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByUserOrderByTradedAtDesc(User user);

    long countByAction(ActionType action);
}
