package com.momentum.controller;

import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import com.momentum.model.enums.TradeStatus;
import com.momentum.repository.DailyTradeRepository;
import com.momentum.repository.UserRepository;
import com.momentum.service.UserAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only history for the automated daily trading engine — separate from the old
 * {@code /​:userId/trades} endpoint (manual buy/sell), which is untouched and keeps working as-is.
 */
@RestController
public class DailyTradingController {

    private final UserRepository userRepository;
    private final DailyTradeRepository dailyTradeRepository;
    private final UserAuthorizationService userAuthorizationService;

    public DailyTradingController(UserRepository userRepository, DailyTradeRepository dailyTradeRepository,
                                   UserAuthorizationService userAuthorizationService) {
        this.userRepository = userRepository;
        this.dailyTradeRepository = dailyTradeRepository;
        this.userAuthorizationService = userAuthorizationService;
    }

    @GetMapping("/{userId}/daily-trades")
    public ResponseEntity<?> getDailyTrades(@PathVariable Long userId) {
        if (!userAuthorizationService.isOwnedByCaller(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("You do not have access to this account"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        List<DailyTradeItem> trades = dailyTradeRepository.findByUserOrderByTradedAtDesc(user).stream()
                .map(trade -> new DailyTradeItem(
                        trade.getSymbol(),
                        trade.getAction(),
                        trade.getStatus(),
                        trade.getAmount(),
                        trade.getPricePerShare(),
                        trade.getQuantity(),
                        trade.getTradedAt(),
                        trade.getIndexFilter()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(trades);
    }

    public record DailyTradeItem(String symbol, ActionType action, TradeStatus status, BigDecimal amount,
                                  BigDecimal pricePerShare, BigDecimal quantity, LocalDateTime tradedAt,
                                  String indexFilter) {
    }

    public record ErrorResponse(String error) {
    }
}
