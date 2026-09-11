package com.momentum.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, auth-free liveness endpoint. Exists specifically for external uptime pingers
 * (UptimeRobot) that need to wake/keep the Render dyno warm without holding a Supabase JWT —
 * every other route requires one. Excluded from both JwtAuthFilter (see its shouldNotFilter)
 * and Spring Security's anyRequest().authenticated() rule (see SecurityConfig).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(new HealthResponse("UP"));
    }

    public record HealthResponse(String status) {
    }
}
