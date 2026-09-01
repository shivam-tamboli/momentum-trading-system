package com.momentum.controller;

import com.momentum.exception.InsufficientBalanceException;
import com.momentum.exception.InvalidTradeAmountException;
import com.momentum.exception.MarketClosedException;
import com.momentum.model.Trade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import com.momentum.repository.TradeRepository;
import com.momentum.repository.UserRepository;
import com.momentum.service.TradeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
public class TradeController {

    private final TradeService tradeService;
    private final TradeRepository tradeRepository;
    private final UserRepository userRepository;

    public TradeController(TradeService tradeService,
                            TradeRepository tradeRepository,
                            UserRepository userRepository) {
        this.tradeService = tradeService;
        this.tradeRepository = tradeRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/{userId}/trade/buy")
    public ResponseEntity<?> buy(@PathVariable Long userId, @RequestBody BuyRequest request) {
        if (userRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            TradeService.BuyResponse response = tradeService.buy(userId, request.amount());

            List<BuyTradeResult> trades = response.trades().stream()
                    .map(t -> new BuyTradeResult(t.symbol(), t.amount(), t.sharesCount(), t.price()))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(new BuyApiResponse(trades));
        } catch (InsufficientBalanceException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (InvalidTradeAmountException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (MarketClosedException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{userId}/trade/sell")
    public ResponseEntity<?> sell(@PathVariable Long userId) {
        if (userRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TradeService.SellResponse response;
        try {
            response = tradeService.sell(userId);
        } catch (MarketClosedException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

        if (response.trades().isEmpty() && response.failures().isEmpty() && response.message() != null) {
            return ResponseEntity.ok(new MessageResponse(response.message()));
        }

        List<SellTradeResult> trades = response.trades().stream()
                .map(t -> new SellTradeResult(t.symbol(), t.sharesCount(), t.amount()))
                .collect(Collectors.toList());

        List<SellFailureResult> failures = response.failures().stream()
                .map(f -> new SellFailureResult(f.symbol(), f.reason()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(new SellApiResponse(trades, failures));
    }

    @GetMapping("/{userId}/trades")
    public ResponseEntity<?> getTrades(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        List<TradeHistoryItem> trades = tradeRepository.findByUserOrderByTradedAtDesc(user).stream()
                .map(trade -> new TradeHistoryItem(
                        trade.getStock().getSymbol(),
                        trade.getAction(),
                        trade.getAmount(),
                        trade.getPricePerShare(),
                        trade.getQuantity(),
                        trade.getTradedAt()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(trades);
    }

    public record BuyRequest(BigDecimal amount) {
    }

    public record BuyTradeResult(String symbol, BigDecimal amountInvested, BigDecimal sharesBought,
                                  BigDecimal price) {
    }

    public record BuyApiResponse(List<BuyTradeResult> trades) {
    }

    public record SellTradeResult(String symbol, BigDecimal sharesSold, BigDecimal amountReceived) {
    }

    public record SellFailureResult(String symbol, String reason) {
    }

    public record SellApiResponse(List<SellTradeResult> trades, List<SellFailureResult> failures) {
    }

    public record MessageResponse(String message) {
    }

    public record ErrorResponse(String error) {
    }

    public record TradeHistoryItem(String symbol, ActionType action, BigDecimal amount, BigDecimal pricePerShare,
                                    BigDecimal quantity, LocalDateTime tradedAt) {
    }
}
