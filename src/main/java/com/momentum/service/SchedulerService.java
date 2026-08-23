package com.momentum.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final MomentumAlgorithmService momentumAlgorithmService;

    public SchedulerService(MomentumAlgorithmService momentumAlgorithmService) {
        this.momentumAlgorithmService = momentumAlgorithmService;
    }

    @Scheduled(cron = "0 0 9 * * MON", zone = "America/New_York")
    public void runWeeklyRecommendations() {
        try {
            log.info("Starting weekly recommendation generation...");
            momentumAlgorithmService.generateWeeklyRecommendations();
            log.info("Weekly recommendation generation completed.");
        } catch (Exception e) {
            log.error("Weekly recommendation generation failed: {}", e.getMessage(), e);
        }
    }
}
