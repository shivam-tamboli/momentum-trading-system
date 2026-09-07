package com.momentum.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.momentum.exception.InvestmentAmountNotSetException;
import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import com.momentum.service.DailyScoringService;
import com.momentum.service.DailyTradingService;
import com.momentum.service.IndexConstituentService;
import com.momentum.util.EncryptionUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Set;

@RestController
public class UserController {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Set<String> VALID_INDEXES = Set.of(
            IndexConstituentService.SP500, IndexConstituentService.NASDAQ100,
            IndexConstituentService.SP400, IndexConstituentService.SP600,
            DailyScoringService.FULL_MARKET);

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final UserRepository userRepository;
    private final EncryptionUtil encryptionUtil;
    private final DailyTradingService dailyTradingService;

    public UserController(UserRepository userRepository, EncryptionUtil encryptionUtil,
                           DailyTradingService dailyTradingService) {
        this.userRepository = userRepository;
        this.encryptionUtil = encryptionUtil;
        this.dailyTradingService = dailyTradingService;
    }

    // Auto-creates a bare user (no Alpaca key yet) on first call for a given email, instead of
    // 404ing — this is what lets "log in" alone land a user on the onboarding screen, without a
    // separate explicit register step.
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String email;
        try {
            email = resolveEmailFromHeader(authHeader);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(null, email, null, null, null, null, null)));

        return ResponseEntity.ok(toMeResponse(user));
    }

    // Used by both the onboarding screen (first key) and Settings (updating it later) — the
    // system then uses whatever's saved here for every future trade, the user never re-enters it.
    @PutMapping("/users/me/alpaca-key")
    public ResponseEntity<?> saveAlpacaKey(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody AlpacaKeyRequest request) {

        String email;
        try {
            email = resolveEmailFromHeader(authHeader);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }

        if (request.alpacaApiKey() == null || request.alpacaApiKey().isBlank()
                || request.alpacaApiSecret() == null || request.alpacaApiSecret().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("alpacaApiKey and alpacaApiSecret are required"));
        }

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(null, email, null, null, null, null, null)));

        user.setAlpacaApiKeyEncrypted(encryptionUtil.encrypt(request.alpacaApiKey()));
        user.setAlpacaApiSecretEncrypted(encryptionUtil.encrypt(request.alpacaApiSecret()));
        User savedUser = userRepository.save(user);

        return ResponseEntity.ok(toMeResponse(savedUser));
    }

    // Change 6: switching index sells everything currently held first, then buys the new
    // index's top 5 — see DailyTradingService.switchIndex for the sell-fully-completes-before-
    // buy-starts ordering. If the user has no key yet (nothing to trade), just records the
    // preference so it's ready whenever they do add one.
    @PostMapping("/users/me/selected-index")
    public ResponseEntity<?> setSelectedIndex(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody SelectedIndexRequest request) {

        String email;
        try {
            email = resolveEmailFromHeader(authHeader);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }

        if (request.selectedIndex() == null || !VALID_INDEXES.contains(request.selectedIndex())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("selectedIndex must be one of: " + VALID_INDEXES));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        boolean hasKey = user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank();
        if (!hasKey) {
            user.setSelectedIndex(request.selectedIndex());
            User saved = userRepository.save(user);
            return ResponseEntity.ok(toMeResponse(saved));
        }

        try {
            dailyTradingService.switchIndex(user.getId(), request.selectedIndex());
        } catch (InvestmentAmountNotSetException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

        User updated = userRepository.findById(user.getId()).orElseThrow();
        return ResponseEntity.ok(toMeResponse(updated));
    }

    // "How much do I want to invest per rebalance cycle" — set once during onboarding or updated
    // from settings. DailyTradingService skips auto-trading entirely for a user until this is set.
    @PutMapping("/users/me/investment-amount")
    public ResponseEntity<?> setInvestmentAmount(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody InvestmentAmountRequest request) {

        String email;
        try {
            email = resolveEmailFromHeader(authHeader);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }

        if (request.investmentAmount() == null || request.investmentAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("investmentAmount must be greater than 0"));
        }

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(null, email, null, null, null, null, null)));

        user.setInvestmentAmount(request.investmentAmount());
        User saved = userRepository.save(user);

        return ResponseEntity.ok(toMeResponse(saved));
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(user.getId(), user.getEmail(),
                user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank(),
                user.getSelectedIndex(), user.getInvestmentAmount());
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

    public record SelectedIndexRequest(@JsonProperty("selectedIndex") String selectedIndex) {
    }

    public record AlpacaKeyRequest(
            @JsonProperty("alpacaApiKey") String alpacaApiKey,
            @JsonProperty("alpacaApiSecret") String alpacaApiSecret) {
    }

    public record InvestmentAmountRequest(@JsonProperty("investmentAmount") BigDecimal investmentAmount) {
    }

    public record MeResponse(Long id, String email, boolean hasAlpacaKey, String selectedIndex,
                              BigDecimal investmentAmount) {
    }

    public record ErrorResponse(String error) {
    }
}
