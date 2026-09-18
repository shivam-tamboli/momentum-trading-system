package com.momentum.repository;

import com.momentum.model.DailyEngineLog;
import com.momentum.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyEngineLogRepository extends JpaRepository<DailyEngineLog, Long> {

    Optional<DailyEngineLog> findByUserAndLogDate(User user, LocalDate logDate);

    List<DailyEngineLog> findTop30ByUserOrderByLogDateDesc(User user);
}
