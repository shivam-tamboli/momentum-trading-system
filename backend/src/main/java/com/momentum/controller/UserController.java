package com.momentum.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import com.momentum.util.EncryptionUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final EncryptionUtil encryptionUtil;

    public UserController(UserRepository userRepository, EncryptionUtil encryptionUtil) {
        this.userRepository = userRepository;
        this.encryptionUtil = encryptionUtil;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String email;
        try {
            email = resolveEmailFromHeader(authHeader);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(new MeResponse(user.getId(), user.getEmail()));
    }

    @PostMapping("/users/register")
    public ResponseEntity<?> register(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody RegisterRequest request) {

        String email;
        try {
            email = resolveEmailFromHeader(authHeader);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }

        User existingUser = userRepository.findByEmail(email).orElse(null);
        if (existingUser != null) {
            return ResponseEntity.ok(new MeResponse(existingUser.getId(), existingUser.getEmail()));
        }

        String encryptedKey = encryptionUtil.encrypt(request.alpacaApiKey());
        String encryptedSecret = encryptionUtil.encrypt(request.alpacaApiSecret());

        User newUser = new User(null, email, encryptedKey, encryptedSecret, null);
        User savedUser = userRepository.save(newUser);

        return ResponseEntity.ok(new MeResponse(savedUser.getId(), savedUser.getEmail()));
    }

    private String resolveEmailFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            return fetchEmailFromSupabase(token);
        } catch (RestClientException e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
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

    private static class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) {
            super(message);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SupabaseUserResponse(String id, String email) {
    }

    public record RegisterRequest(String alpacaApiKey, String alpacaApiSecret) {
    }

    public record MeResponse(Long id, String email) {
    }

    public record ErrorResponse(String error) {
    }
}
