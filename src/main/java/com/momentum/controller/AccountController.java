package com.momentum.controller;

import com.momentum.config.AlpacaConfig;
import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import com.momentum.util.EncryptionUtil;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.account.Account;
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

    public AccountController(UserRepository userRepository,
                              AlpacaConfig alpacaConfig,
                              EncryptionUtil encryptionUtil) {
        this.userRepository = userRepository;
        this.alpacaConfig = alpacaConfig;
        this.encryptionUtil = encryptionUtil;
    }

    @GetMapping("/{userId}/account")
    public ResponseEntity<?> getAccount(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);

        try {
            Account account = userAlpacaAPI.account().get();

            AccountResponse response = new AccountResponse(
                    new BigDecimal(account.getCash()),
                    new BigDecimal(account.getBuyingPower()),
                    new BigDecimal(account.getPortfolioValue())
            );

            return ResponseEntity.ok(response);
        } catch (AlpacaClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch account from Alpaca: " + e.getMessage()));
        }
    }

    @GetMapping("/{userId}/positions")
    public ResponseEntity<?> getPositions(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);

        try {
            List<Position> positions = userAlpacaAPI.positions().get();

            List<PositionResponse> response = positions.stream()
                    .map(position -> new PositionResponse(
                            position.getSymbol(),
                            new BigDecimal(position.getQuantity()),
                            new BigDecimal(position.getAverageEntryPrice()),
                            new BigDecimal(position.getCurrentPrice()),
                            new BigDecimal(position.getUnrealizedProfitLoss())
                    ))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (AlpacaClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch positions from Alpaca: " + e.getMessage()));
        }
    }

    private AlpacaAPI buildUserAlpacaAPI(User user) {
        String apiKey = encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted());
        String apiSecret = encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted());
        return alpacaConfig.createUserAlpacaAPI(apiKey, apiSecret);
    }

    public record AccountResponse(BigDecimal cash, BigDecimal buyingPower, BigDecimal portfolioValue) {
    }

    public record PositionResponse(String symbol, BigDecimal qty, BigDecimal avgEntryPrice,
                                    BigDecimal currentPrice, BigDecimal unrealizedPl) {
    }

    public record ErrorResponse(String error) {
    }
}
