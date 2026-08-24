package com.momentum.service;

import com.momentum.config.AlpacaConfig;
import com.momentum.exception.InsufficientBalanceException;
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
        List<Recommendation> buyRecommendations =
                recommendationRepository.findByActionAndWeekDate(ActionType.BUY, weekDate);

        if (buyRecommendations.isEmpty()) {
            return new BuyResponse(List.of());
        }

        BigDecimal amountPerStock = amount.divide(
                BigDecimal.valueOf(buyRecommendations.size()), 2, RoundingMode.DOWN);

        List<TradeResult> results = new ArrayList<>();

        for (Recommendation recommendation : buyRecommendations) {
            Stock stock = recommendation.getStock();
            try {
                // requestNotionalMarketOrder() hardcodes OrderTimeInForce.GOOD_UNTIL_CANCELLED, but Alpaca
                // rejects fractional/notional orders with GTC (error 42210000) — they must be DAY orders.
                Order order = userAlpacaAPI.orders().requestOrder(
                        stock.getSymbol(), null, amountPerStock.doubleValue(), OrderSide.BUY,
                        OrderType.MARKET, OrderTimeInForce.DAY,
                        null, null, null, null, null, null, null, null, null, null);

                // Alpaca fills orders asynchronously; the immediate POST /orders response often
                // doesn't include fill data yet. Wait briefly, then re-fetch the order to pick it up.
                Thread.sleep(1500);
                try {
                    Order filledOrder = userAlpacaAPI.orders().get(order.getId(), false);
                    if (filledOrder != null && filledOrder.getAverageFillPrice() != null) {
                        order = filledOrder;
                    }
                } catch (Exception e) {
                    log.warn("Failed to re-fetch order {} for {} (user {}): {}",
                            order.getId(), stock.getSymbol(), userId, e.getMessage());
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

                results.add(new TradeResult(stock.getSymbol(), amountPerStock, filledQty, filledPrice));
            } catch (Exception e) {
                log.warn("Buy order failed for {} (user {}): {}", stock.getSymbol(), userId, e.getMessage(), e);
            }
        }

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

        LocalDate weekDate = getCurrentWeekMonday();
        List<Recommendation> sellRecommendations =
                recommendationRepository.findByActionAndWeekDate(ActionType.SELL, weekDate);

        Map<String, Recommendation> sellRecommendationsBySymbol = sellRecommendations.stream()
                .collect(Collectors.toMap(recommendation -> recommendation.getStock().getSymbol(),
                        recommendation -> recommendation));

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
            return new SellResponse(List.of(), "No positions match this week's sell recommendations");
        }

        List<TradeResult> results = new ArrayList<>();

        for (Position position : matchedPositions) {
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
                // doesn't include fill data yet. Wait briefly, then re-fetch the order to pick it up.
                Thread.sleep(1500);
                try {
                    Order filledOrder = userAlpacaAPI.orders().get(order.getId(), false);
                    if (filledOrder != null && filledOrder.getAverageFillPrice() != null) {
                        order = filledOrder;
                    }
                } catch (Exception e) {
                    log.warn("Failed to re-fetch order {} for {} (user {}): {}",
                            order.getId(), stock.getSymbol(), userId, e.getMessage());
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

                results.add(new TradeResult(stock.getSymbol(), amountReceived, filledQty, filledPrice));
            } catch (Exception e) {
                log.warn("Sell order failed for {} (user {}): {}", stock.getSymbol(), userId, e.getMessage());
            }
        }

        return new SellResponse(results, null);
    }

    private LocalDate getCurrentWeekMonday() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public record TradeResult(String symbol, BigDecimal amount, BigDecimal sharesCount, BigDecimal price) {
    }

    public record BuyResponse(List<TradeResult> trades) {
    }

    public record SellResponse(List<TradeResult> trades, String message) {
    }
}
