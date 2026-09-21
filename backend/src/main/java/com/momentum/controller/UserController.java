package com.momentum.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.momentum.exception.InvestmentAmountNotSetException;
import com.momentum.model.IndexSwitchHistory;
import com.momentum.model.User;
import com.momentum.repository.IndexSwitchHistoryRepository;
import com.momentum.repository.UserRepository;
import com.momentum.service.DailyScoringService;
import com.momentum.service.DailyTradingService;
import com.momentum.service.IndexConstituentService;
import com.momentum.util.EncryptionUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@RestController
public class UserController {

    private static final Set<String> VALID_INDEXES = Set.of(
            IndexConstituentService.SP500, IndexConstituentService.NASDAQ100,
            IndexConstituentService.SP400, IndexConstituentService.SP600,
            DailyScoringService.FULL_MARKET);

    private final UserRepository userRepository;
    private final EncryptionUtil encryptionUtil;
    private final DailyTradingService dailyTradingService;
    private final IndexSwitchHistoryRepository indexSwitchHistoryRepository;

    public UserController(UserRepository userRepository, EncryptionUtil encryptionUtil,
                           DailyTradingService dailyTradingService,
                           IndexSwitchHistoryRepository indexSwitchHistoryRepository) {
        this.userRepository = userRepository;
        this.encryptionUtil = encryptionUtil;
        this.dailyTradingService = dailyTradingService;
        this.indexSwitchHistoryRepository = indexSwitchHistoryRepository;
    }

    // Auto-creates a bare user (no Alpaca key yet) on first call for a given email, instead of
    // 404ing — this is what lets "log in" alone land a user on the onboarding screen, without a
    // separate explicit register step.
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        User user = findOrCreateUser(currentEmail());
        return ResponseEntity.ok(toMeResponse(user));
    }

    // Used by both the onboarding screen (first key) and Settings (updating it later) — the
    // system then uses whatever's saved here for every future trade, the user never re-enters it.
    @PutMapping("/users/me/alpaca-key")
    public ResponseEntity<?> saveAlpacaKey(@RequestBody AlpacaKeyRequest request) {
        if (request.alpacaApiKey() == null || request.alpacaApiKey().isBlank()
                || request.alpacaApiSecret() == null || request.alpacaApiSecret().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("alpacaApiKey and alpacaApiSecret are required"));
        }

        User user = findOrCreateUser(currentEmail());

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
    public ResponseEntity<?> setSelectedIndex(@RequestBody SelectedIndexRequest request) {
        if (request.selectedIndex() == null || !VALID_INDEXES.contains(request.selectedIndex())) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("selectedIndex must be one of: " + VALID_INDEXES));
        }

        User user = userRepository.findByEmail(currentEmail()).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        String previousIndex = user.getSelectedIndex();
        // Captured now, before switchIndex() places any trades — that call sells then buys
        // sequentially across up to 10 legs and can take real wall-clock time (seconds). If the
        // user changes their investment amount while that's in flight, a value re-read afterward
        // would reflect the NEW amount, not what was actually used to size these trades. This
        // local copy is what the history row uses, regardless of how long the switch takes.
        BigDecimal investmentAmountAtSwitch = user.getInvestmentAmount();

        boolean hasKey = user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank();
        if (!hasKey) {
            user.setSelectedIndex(request.selectedIndex());
            User saved = userRepository.save(user);
            recordIndexSwitch(saved, previousIndex, request.selectedIndex(), investmentAmountAtSwitch);
            return ResponseEntity.ok(toMeResponse(saved));
        }

        try {
            dailyTradingService.switchIndex(user.getId(), request.selectedIndex());
        } catch (InvestmentAmountNotSetException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

        User updated = userRepository.findById(user.getId()).orElseThrow();
        recordIndexSwitch(updated, previousIndex, request.selectedIndex(), investmentAmountAtSwitch);
        return ResponseEntity.ok(toMeResponse(updated));
    }

    // Written on every selected_index change, including a user's very first pick (previousIndex
    // null). investmentAmount is passed in explicitly — always the value captured at the moment
    // the switch was initiated, never re-read afterward — so it can't drift from what the trades
    // actually used even if the amount changes later in the same request or right after.
    private void recordIndexSwitch(User user, String previousIndex, String newIndex, BigDecimal investmentAmount) {
        indexSwitchHistoryRepository.save(
                new IndexSwitchHistory(null, user, previousIndex, newIndex, investmentAmount, null));
    }

    @GetMapping("/users/me/index-switch-history")
    public ResponseEntity<?> getIndexSwitchHistory() {
        User user = userRepository.findByEmail(currentEmail()).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        List<IndexSwitchHistoryItem> history = indexSwitchHistoryRepository
                .findByUserOrderBySwitchedAtDesc(user).stream()
                .map(h -> new IndexSwitchHistoryItem(
                        h.getPreviousIndex(), h.getNewIndex(), h.getInvestmentAmount(), h.getSwitchedAt()))
                .toList();

        return ResponseEntity.ok(history);
    }

    // "How much do I want to invest per rebalance cycle" — set once during onboarding or updated
    // from settings. DailyTradingService skips auto-trading entirely for a user until this is set.
    @PutMapping("/users/me/investment-amount")
    public ResponseEntity<?> setInvestmentAmount(@RequestBody InvestmentAmountRequest request) {
        if (request.investmentAmount() == null || request.investmentAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("investmentAmount must be greater than 0"));
        }

        User user = findOrCreateUser(currentEmail());

        user.setInvestmentAmount(request.investmentAmount());
        User saved = userRepository.save(user);

        return ResponseEntity.ok(toMeResponse(saved));
    }

    // JwtAuthFilter has already verified the token against Supabase and set the resolved email as
    // the authentication principal before any request reaches this controller — re-verifying it
    // here would just be a second, redundant round-trip to Supabase for the same token.
    private String currentEmail() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    // Guards against the race where two near-simultaneous first requests for a brand-new email
    // (e.g. UserProvider resolving on mount and again on a Supabase auth-state-change event
    // right after sign-in) both miss findByEmail and both try to insert. Whichever loses hits the
    // unique constraint on email instead of the request failing outright — by the time that
    // exception is thrown, the winner's row is already committed, so re-fetching finds it.
    private User findOrCreateUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            try {
                return userRepository.save(new User(null, email, null, null, null, null, null));
            } catch (DataIntegrityViolationException e) {
                return userRepository.findByEmail(email).orElseThrow(() -> e);
            }
        });
    }

    private MeResponse toMeResponse(User user) {
        return new MeResponse(user.getId(), user.getEmail(),
                user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank(),
                user.getSelectedIndex(), user.getInvestmentAmount());
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

    public record IndexSwitchHistoryItem(String previousIndex, String newIndex,
                                          BigDecimal investmentAmount, LocalDateTime switchedAt) {
    }

    public record ErrorResponse(String error) {
    }
}
