package com.momentum.controller;

import com.momentum.service.MomentumAlgorithmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final MomentumAlgorithmService momentumAlgorithmService;

    public AdminController(MomentumAlgorithmService momentumAlgorithmService) {
        this.momentumAlgorithmService = momentumAlgorithmService;
    }

    @PostMapping("/run-algorithm")
    public ResponseEntity<String> runAlgorithm() {
        momentumAlgorithmService.generateWeeklyRecommendations();
        return ResponseEntity.ok("Algorithm completed successfully");
    }
}
