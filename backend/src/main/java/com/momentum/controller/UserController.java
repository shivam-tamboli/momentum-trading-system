package com.momentum.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
public class UserController {

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        String email;
        try {
            email = fetchEmailFromSupabase(token);
        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Invalid or expired token"));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(new MeResponse(user.getId(), user.getEmail()));
    }

    private String fetchEmailFromSupabase(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", BEARER_PREFIX + token);
        headers.set("apikey", supabaseAnonKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<SupabaseUserResponse> response = restTemplate.exchange(
                supabaseUrl + "/auth/v1/user",
                HttpMethod.GET,
                entity,
                SupabaseUserResponse.class
        );

        SupabaseUserResponse body = response.getBody();
        if (body == null || body.email() == null) {
            throw new RestClientException("Supabase response missing email");
        }

        return body.email();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SupabaseUserResponse(String id, String email) {
    }

    public record MeResponse(Long id, String email) {
    }

    public record ErrorResponse(String error) {
    }
}
