package com.momentum.service;

import com.momentum.config.AlpacaConfig;
import com.momentum.exception.InsufficientBalanceException;
import com.momentum.exception.InvalidTradeAmountException;
import com.momentum.exception.MarketClosedException;
import com.momentum.model.Recommendation;
import com.momentum.model.Stock;
import com.momentum.model.Trade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import com.momentum.repository.RecommendationRepository;
import com.momentum.repository.TradeRepository;
import com.momentum.repository.UserRepository;
import com.momentum.util.EncryptionUtil;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.account.Account;
import net.jacobpeterson.alpaca.model.endpoint.clock.Clock;
import net.jacobpeterson.alpaca.model.endpoint.orders.Order;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderSide;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderTimeInForce;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderType;
import net.jacobpeterson.alpaca.model.endpoint.positions.Position;
import net.jacobpeterson.alpaca.rest.AlpacaClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;
    private final RecommendationRepository recommendationRepository;
    private final AlpacaConfig alpacaConfig;
    private final EncryptionUtil encryptionUtil;

    public TradeService(UserRepository userRepository,
                         TradeRepository tradeRepository,
                         RecommendationRepository recommendationRepository,
                         AlpacaConfig alpacaConfig,
                         EncryptionUtil encryptionUtil) {
        this.userRepository = userRepository;
        this.tradeRepository = tradeRepository;
        this.recommendationRepository = recommendationRepository;
        this.alpacaConfig = alpacaConfig;
        this.encryptionUtil = encryptionUtil;
    }

    public BuyResponse buy(Long userId, BigDecimal amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String apiKey = encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted());
        String apiSecret = encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted());

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Decryption returned null — ENCRYPTION_KEY mismatch or corrupted data");
        }

        log.info("Creating user AlpacaAPI, apiKey starts with: {}",
                apiKey == null ? "null" : apiKey.substring(0, Math.min(5, apiKey.length())));

        log.info("apiKey starts with: {}",
                apiKey == null ? "NULL" : apiKey.substring(0, Math.min(5, apiKey.length())));
        log.info("apiSecret is null: {}", apiSecret == null);
        log.info("apiSecret length: {}", apiSecret == null ? 0 : apiSecret.length());

        AlpacaAPI userAlpacaAPI;
        try {
            userAlpacaAPI = alpacaConfig.createUserAlpacaAPI(apiKey, apiSecret);
        } catch (Exception e) {
            log.error("Failed to create AlpacaAPI", e);
            throw e;
        }

        checkMarketOpen(userAlpacaAPI);

        BigDecimal buyingPower;
        try {
            Account account = userAlpacaAPI.account().get();
            buyingPower = new BigDecimal(account.getBuyingPower());
        } catch (AlpacaClientException e) {
            throw new RuntimeException("Failed to fetch Alpaca account for user " + userId, e);
        }

        if (amount.compareTo(buyingPower) > 0) {
            throw new InsufficientBalanceException("Insufficient balance in your Alpaca account");
        }

        LocalDate weekDate = getCurrentWeekMonday();

        // Defensive dedup: keyed by (symbol + indexName) so a stock that's legitimately a BUY
        // under two different indexes (e.g. MSFT in both S&P 500 and Nasdaq 100) still counts as
        // two distinct recommendations, while duplicate rows from re-running the algorithm for the
        // same week (same symbol + same index) collapse to one instead of inflating the divisor.
        List<Recommendation> buyRecommendations = new ArrayList<>(
                recommendationRepository.findByActionAndWeekDate(ActionType.BUY, weekDate).stream()
                        .collect(Collectors.toMap(
                                recommendation -> recommendation.getStock().getSymbol() + "|" + recommendation.getIndexName(),
                                recommendation -> recommendation,
                                (existing, replacement) -> replacement))
                        .values());

        if (buyRecommendations.isEmpty()) {
            return new BuyResponse(List.of());
        }

        int recommendationCount = buyRecommendations.size();
        BigDecimal amountPerStock = amount.divide(
                BigDecimal.valueOf(recommendationCount), 2, RoundingMode.DOWN);

        if (amountPerStock.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidTradeAmountException(
                    "Amount too small: $" + amount + " split across " + recommendationCount
                            + " BUY recommendation(s) is $" + amountPerStock + " per stock, below Alpaca's "
                            + "$1.00 minimum notional. Enter at least $" + recommendationCount + ".00.");
        }

        // Placed in parallel rather than one stock at a time: sequentially, a full BUY list can take
        // several minutes (each order can wait up to 24s for a fill via waitForFill), long enough that
        // the connection gets cut before the response reaches the browser even though the backend keeps
        // running and saves everything correctly. In parallel, total wall-clock time is bounded by the
        // single slowest order instead of the sum of all of them.
        List<TradeResult> results = buyRecommendations.parallelStream()
                .map(recommendation -> {
                    Stock stock = recommendation.getStock();
                    try {
                        // requestNotionalMarketOrder() hardcodes OrderTimeInForce.GOOD_UNTIL_CANCELLED, but Alpaca
                        // rejects fractional/notional orders with GTC (error 42210000) — they must be DAY orders.
                        Order order = userAlpacaAPI.orders().requestOrder(
                                stock.getSymbol(), null, amountPerStock.doubleValue(), OrderSide.BUY,
                                OrderType.MARKET, OrderTimeInForce.DAY,
                                null, null, null, null, null, null, null, null, null, null);

                        // Alpaca fills orders asynchronously; the immediate POST /orders response often
                        // doesn't include fill data yet. Poll for the fill, then re-fetch the order to pick it up.
                        Order filledOrder = waitForFill(userAlpacaAPI, order.getId());
                        if (filledOrder != null) {
                            order = filledOrder;
                        }

                        String fillPriceStr = order.getAverageFillPrice();
                        String fillQtyStr = order.getFilledQuantity();

                        BigDecimal filledPrice = (fillPriceStr != null && !fillPriceStr.isEmpty())
                                ? new BigDecimal(fillPriceStr)
                                : BigDecimal.ZERO;

                        BigDecimal filledQty = (fillQtyStr != null && !fillQtyStr.isEmpty())
                                ? new BigDecimal(fillQtyStr)
                                : BigDecimal.ZERO;

                        Trade trade = new Trade(
                                null,
                                user,
                                stock,
                                recommendation,
                                ActionType.BUY,
                                amountPerStock,
                                filledPrice,
                                filledQty,
                                order.getId(),
                                null
                        );
                        tradeRepository.save(trade);

                        return new TradeResult(stock.getSymbol(), amountPerStock, filledQty, filledPrice);
                    } catch (Exception e) {
                        log.warn("Buy order failed for {} (user {}): {}", stock.getSymbol(), userId, e.getMessage(), e);
                        return null;
                    }
                })
                .filter(result -> result != null)
                .collect(Collectors.toList());

        log.info("Buy complete: {} orders placed, {} filled with price data",
                results.size(),
                results.stream().filter(r -> r.price().compareTo(BigDecimal.ZERO) > 0).count());

        return new BuyResponse(results);
    }

    public SellResponse sell(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String apiKey = encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted());
        String apiSecret = encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted());

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Decryption returned null — ENCRYPTION_KEY mismatch or corrupted data");
        }

        log.info("Creating user AlpacaAPI, apiKey starts with: {}",
                apiKey == null ? "null" : apiKey.substring(0, Math.min(5, apiKey.length())));
        AlpacaAPI userAlpacaAPI = alpacaConfig.createUserAlpacaAPI(apiKey, apiSecret);

        checkMarketOpen(userAlpacaAPI);

        LocalDate weekDate = getCurrentWeekMonday();
        List<Recommendation> sellRecommendations =
                recommendationRepository.findByActionAndWeekDate(ActionType.SELL, weekDate);

        Map<String, Recommendation> sellRecommendationsBySymbol = sellRecommendations.stream()
                .collect(Collectors.toMap(recommendation -> recommendation.getStock().getSymbol(),
                        recommendation -> recommendation,
                        (existing, replacement) -> replacement));

        List<Position> positions;
        try {
            positions = userAlpacaAPI.positions().get();
        } catch (AlpacaClientException e) {
            throw new RuntimeException("Failed to fetch Alpaca positions for user " + userId, e);
        }

        List<Position> matchedPositions = positions.stream()
                .filter(position -> sellRecommendationsBySymbol.containsKey(position.getSymbol()))
                .collect(Collectors.toList());

        if (matchedPositions.isEmpty()) {
            return new SellResponse(List.of(), "No positions match this week's sell recommendations", List.of());
        }

        // Placed in parallel for the same reason as buy(): sequentially, this loop can take long enough
        // (up to 24s per order) that the connection gets cut before the response reaches the browser.
        List<SellOutcome> outcomes = matchedPositions.parallelStream()
                .map(position -> {
                    Recommendation recommendation = sellRecommendationsBySymbol.get(position.getSymbol());
                    Stock stock = recommendation.getStock();
                    try {
                        BigDecimal quantity = new BigDecimal(position.getQuantity());

                        // requestFractionalMarketOrder() hardcodes OrderTimeInForce.GOOD_UNTIL_CANCELLED, but Alpaca
                        // rejects fractional/notional orders with GTC (error 42210000) — they must be DAY orders.
                        Order order = userAlpacaAPI.orders().requestOrder(
                                stock.getSymbol(), quantity.doubleValue(), null, OrderSide.SELL,
                                OrderType.MARKET, OrderTimeInForce.DAY,
                                null, null, null, null, null, null, null, null, null, null);

                        // Alpaca fills orders asynchronously; the immediate POST /orders response often
                        // doesn't include fill data yet. Poll for the fill, then re-fetch the order to pick it up.
                        Order filledOrder = waitForFill(userAlpacaAPI, order.getId());
                        if (filledOrder != null) {
                            order = filledOrder;
                        }

                        String fillPriceStr = order.getAverageFillPrice();
                        String fillQtyStr = order.getFilledQuantity();

                        BigDecimal filledPrice = (fillPriceStr != null && !fillPriceStr.isEmpty())
                                ? new BigDecimal(fillPriceStr)
                                : BigDecimal.ZERO;

                        BigDecimal filledQty = (fillQtyStr != null && !fillQtyStr.isEmpty())
                                ? new BigDecimal(fillQtyStr)
                                : BigDecimal.ZERO;

                        BigDecimal amountReceived = filledPrice.multiply(filledQty);

                        Trade trade = new Trade(
                                null,
                                user,
                                stock,
                                recommendation,
                                ActionType.SELL,
                                amountReceived,
                                filledPrice,
                                filledQty,
                                order.getId(),
                                null
                        );
                        tradeRepository.save(trade);

                        return new SellOutcome(
                                new TradeResult(stock.getSymbol(), amountReceived, filledQty, filledPrice), null);
                    } catch (Exception e) {
                        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.warn("Sell order failed for {} (user {}): {}", stock.getSymbol(), userId, reason);
                        return new SellOutcome(null, new SellFailure(stock.getSymbol(), reason));
                    }
                })
                .collect(Collectors.toList());

        List<TradeResult> results = outcomes.stream()
                .map(SellOutcome::result)
                .filter(result -> result != null)
                .collect(Collectors.toList());

        List<SellFailure> failures = outcomes.stream()
                .map(SellOutcome::failure)
                .filter(failure -> failure != null)
                .collect(Collectors.toList());

        return new SellResponse(results, null, failures);
    }

    // A DAY market order placed while the market is closed won't fill within waitForFill()'s
    // 24-second window — it just queues until the next open, which can be hours away. Rather than
    // let that silently produce a trade row with $0.00/0 quantity, reject the request up front with
    // a clear reason.
    private void checkMarketOpen(AlpacaAPI alpacaAPI) {
        Clock clock;
        try {
            clock = alpacaAPI.clock().get();
        } catch (AlpacaClientException e) {
            throw new RuntimeException("Failed to fetch Alpaca market clock", e);
        }

        if (clock.getIsOpen() == null || !clock.getIsOpen()) {
            throw new MarketClosedException(
                    "Market is closed. It reopens at " + clock.getNextOpen() + ".");
        }
    }

    private Order waitForFill(AlpacaAPI alpacaAPI, String orderId) {
        for (int i = 0; i < 8; i++) {
            log.info("Waiting for fill on order {} - attempt {}/8", orderId, i + 1);
            try {
                Thread.sleep(3000);
                Order order = alpacaAPI.orders().get(orderId, false);
                if (order != null && order.getAverageFillPrice() != null
                        && !order.getAverageFillPrice().isEmpty()) {
                    return order;
                }
            } catch (Exception e) {
                log.warn("Retry {} waiting for fill on order {}", i + 1, orderId);
            }
        }
        return null;
    }

    private LocalDate getCurrentWeekMonday() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public record TradeResult(String symbol, BigDecimal amount, BigDecimal sharesCount, BigDecimal price) {
    }

    public record BuyResponse(List<TradeResult> trades) {
    }

    public record SellFailure(String symbol, String reason) {
    }

    private record SellOutcome(TradeResult result, SellFailure failure) {
    }

    public record SellResponse(List<TradeResult> trades, String message, List<SellFailure> failures) {
    }
}
