package com.momentum.controller;

import com.momentum.config.AlpacaConfig;
import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import com.momentum.service.UserAuthorizationService;
import com.momentum.util.EncryptionUtil;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.account.Account;
import net.jacobpeterson.alpaca.model.endpoint.assets.Asset;
import net.jacobpeterson.alpaca.model.endpoint.positions.Position;
import net.jacobpeterson.alpaca.rest.AlpacaClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@RestController
public class AccountController {

    private final UserRepository userRepository;
    private final AlpacaConfig alpacaConfig;
    private final EncryptionUtil encryptionUtil;
    private final UserAuthorizationService userAuthorizationService;

    public AccountController(UserRepository userRepository,
                              AlpacaConfig alpacaConfig,
                              EncryptionUtil encryptionUtil,
                              UserAuthorizationService userAuthorizationService) {
        this.userRepository = userRepository;
        this.alpacaConfig = alpacaConfig;
        this.encryptionUtil = encryptionUtil;
        this.userAuthorizationService = userAuthorizationService;
    }

    @GetMapping("/{userId}/account")
    public ResponseEntity<?> getAccount(@PathVariable Long userId) {
        if (!userAuthorizationService.isOwnedByCaller(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("You do not have access to this account"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);

        try {
            Account account = userAlpacaAPI.account().get();

            // lastEquity is Alpaca's own record of the previous trading day's closing equity —
            // real, not derived or estimated — so the frontend can show a day-over-day delta
            // without needing any historical snapshot storage of our own.
            AccountResponse response = new AccountResponse(
                    new BigDecimal(account.getCash()),
                    new BigDecimal(account.getBuyingPower()),
                    new BigDecimal(account.getPortfolioValue()),
                    new BigDecimal(account.getLastEquity())
            );

            return ResponseEntity.ok(response);
        } catch (AlpacaClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch account from Alpaca: " + e.getMessage()));
        }
    }

    @GetMapping("/{userId}/positions")
    public ResponseEntity<?> getPositions(@PathVariable Long userId) {
        if (!userAuthorizationService.isOwnedByCaller(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("You do not have access to this account"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);

        try {
            List<Position> positions = userAlpacaAPI.positions().get();

            // Alpaca's Position model has no company name field at all, only the symbol — a
            // separate lookup is the only way to get it. The daily engine only ever holds up to
            // 5 positions (top-5-per-index design), so this is a small, bounded number of extra
            // calls, done in parallel rather than serially.
            List<PositionResponse> response = positions.parallelStream()
                    .map(position -> new PositionResponse(
                            position.getSymbol(),
                            fetchCompanyName(userAlpacaAPI, position.getSymbol()),
                            new BigDecimal(position.getQuantity()),
                            new BigDecimal(position.getAverageEntryPrice()),
                            new BigDecimal(position.getCurrentPrice()),
                            new BigDecimal(position.getMarketValue()),
                            new BigDecimal(position.getUnrealizedProfitLoss()),
                            new BigDecimal(position.getUnrealizedProfitLossPercent())
                    ))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (AlpacaClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch positions from Alpaca: " + e.getMessage()));
        }
    }

    // Best-effort — a failed name lookup for one symbol falls back to the symbol itself rather
    // than failing the whole positions response over a non-essential display detail.
    private String fetchCompanyName(AlpacaAPI alpacaAPI, String symbol) {
        try {
            Asset asset = alpacaAPI.assets().getBySymbol(symbol);
            return (asset != null && asset.getName() != null) ? asset.getName() : symbol;
        } catch (Exception e) {
            return symbol;
        }
    }

    private AlpacaAPI buildUserAlpacaAPI(User user) {
        String apiKey = encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted());
        String apiSecret = encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted());
        return alpacaConfig.createUserAlpacaAPI(apiKey, apiSecret);
    }

    public record AccountResponse(BigDecimal cash, BigDecimal buyingPower, BigDecimal portfolioValue,
                                   BigDecimal lastEquity) {
    }

    public record PositionResponse(String symbol, String name, BigDecimal qty, BigDecimal avgEntryPrice,
                                    BigDecimal currentPrice, BigDecimal marketValue, BigDecimal unrealizedPl,
                                    BigDecimal unrealizedPlPercent) {
    }

    public record ErrorResponse(String error) {
    }
}
