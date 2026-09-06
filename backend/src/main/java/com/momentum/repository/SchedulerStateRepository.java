package com.momentum.repository;

import com.momentum.model.SchedulerState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulerStateRepository extends JpaRepository<SchedulerState, Long> {
}
