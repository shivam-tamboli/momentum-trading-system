package com.momentum.service;

import com.momentum.config.AlpacaConfig;
import com.momentum.exception.InvestmentAmountNotSetException;
import com.momentum.exception.MarketClosedException;
import com.momentum.model.DailyRecommendation;
import com.momentum.model.DailyTrade;
import com.momentum.model.User;
import com.momentum.model.enums.ActionType;
import com.momentum.model.enums.TradeStatus;
import com.momentum.repository.DailyRecommendationRepository;
import com.momentum.repository.DailyTradeRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Job 2 of the daily engine. For every user with a saved Alpaca key and a chosen index filter:
 * compares their live Alpaca positions (source of truth, never our DB) against today's top 5 for
 * that filter, sells whatever dropped out, buys whatever's newly in, leaves the rest untouched.
 *
 * Has its own audit table, market-open check, and fill-wait helper rather than sharing them with
 * anything else — this is the only automated trading path in the system.
 */
@Service
public class DailyTradingService {

    private static final Logger log = LoggerFactory.getLogger(DailyTradingService.class);

    private final UserRepository userRepository;
    private final DailyRecommendationRepository dailyRecommendationRepository;
    private final DailyTradeRepository dailyTradeRepository;
    private final AlpacaConfig alpacaConfig;
    private final EncryptionUtil encryptionUtil;
    private final EmailService emailService;

    public DailyTradingService(UserRepository userRepository,
                                DailyRecommendationRepository dailyRecommendationRepository,
                                DailyTradeRepository dailyTradeRepository,
                                AlpacaConfig alpacaConfig,
                                EncryptionUtil encryptionUtil,
                                EmailService emailService) {
        this.userRepository = userRepository;
        this.dailyRecommendationRepository = dailyRecommendationRepository;
        this.dailyTradeRepository = dailyTradeRepository;
        this.alpacaConfig = alpacaConfig;
        this.encryptionUtil = encryptionUtil;
        this.emailService = emailService;
    }

    public void runDailyTrading() {
        List<User> eligibleUsers = userRepository.findAll().stream()
                .filter(this::hasApiKey)
                .filter(u -> u.getSelectedIndex() != null && !u.getSelectedIndex().isBlank())
                .collect(Collectors.toList());

        log.info("Daily trading: {} users eligible (have a key and a selected index)", eligibleUsers.size());

        eligibleUsers.parallelStream().forEach(this::rebalanceUser);
    }

    private void rebalanceUser(User user) {
        if (user.getInvestmentAmount() == null) {
            log.info("Daily trading: skipping auto-trading for user {} — no investment_amount set", user.getId());
            return;
        }

        try {
            AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);
            checkMarketOpen(userAlpacaAPI);

            List<Position> positions = fetchPositions(userAlpacaAPI, user.getId());
            Set<String> heldSymbols = positions.stream().map(Position::getSymbol).collect(Collectors.toSet());

            List<DailyRecommendation> top5 =
                    dailyRecommendationRepository.findByFilterNameOrderByMomentumScoreDesc(user.getSelectedIndex());
            Set<String> top5Symbols = top5.stream().map(DailyRecommendation::getSymbol).collect(Collectors.toSet());

            Set<String> toSell = new HashSet<>(heldSymbols);
            toSell.removeAll(top5Symbols);

            Set<String> toBuy = new HashSet<>(top5Symbols);
            toBuy.removeAll(heldSymbols);

            log.info("User {}: {} held, {} in top 5 ({}), {} to sell, {} to buy", user.getId(),
                    heldSymbols.size(), top5Symbols.size(), user.getSelectedIndex(), toSell.size(), toBuy.size());

            Map<String, Position> positionsBySymbol = positions.stream()
                    .collect(Collectors.toMap(Position::getSymbol, p -> p));

            // Single index for this whole run — both sides of the diff belong to it equally.
            List<DailyTrade> sold = sellSymbols(userAlpacaAPI, user, toSell, positionsBySymbol, user.getSelectedIndex());
            List<DailyTrade> bought = buySymbols(userAlpacaAPI, user, toBuy, top5Symbols.size(), user.getSelectedIndex());

            if (!sold.isEmpty() || !bought.isEmpty()) {
                BigDecimal portfolioValue = fetchPortfolioValue(userAlpacaAPI, user.getId());
                emailService.sendPortfolioRebalancedEmail(user, bought, sold, portfolioValue,
                        LocalDateTime.now(ZoneOffset.UTC));
            }
        } catch (MarketClosedException e) {
            log.info("Daily trading: skipping user {} — {}", user.getId(), e.getMessage());
        } catch (Exception e) {
            log.error("Daily trading failed for user {}: {}", user.getId(), e.getMessage(), e);
        }
    }

    /**
     * Full liquidate-then-reallocate for a user changing their selected index (Change 6). Unlike
     * the regular daily rebalance (which only touches the delta), this sells 100% of current
     * holdings — the sell phase fully completes (parallelStream blocks the caller until every
     * order in it is done) before the buy phase for the new index starts.
     *
     * The preference itself is saved unconditionally, market open or not — a user changing their
     * mind about which index to follow must never get an error just for that. Only the immediate
     * sell-then-buy is gated on the market being open; if it's closed, the new preference is
     * already in place for the scheduler to pick up at the next Job 2 run, no trading happens now.
     */
    public void switchIndex(Long userId, String newIndex) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Checked before anything else happens — no sell, no buy, no state change — since the buy
        // phase below has no dollar amount to work with otherwise.
        if (user.getInvestmentAmount() == null) {
            throw new InvestmentAmountNotSetException(
                    "Please set your investment amount in settings before switching index.");
        }

        // Captured before the overwrite below — the positions about to be sold were bought under
        // this index, not the new one, even though selected_index itself changes right away.
        String previousIndex = user.getSelectedIndex();

        user.setSelectedIndex(newIndex);
        userRepository.save(user);

        List<DailyTrade> sold = List.of();
        List<DailyTrade> bought = List.of();
        boolean marketWasOpen = true;

        // Everything past this point is a best-effort immediate rebalance, not part of "did the
        // preference save succeed." Market-closed is the expected, common case (logged as info);
        // any other failure here (Alpaca outage, bad credentials, etc.) is logged but never
        // propagated — the preference is already durably saved, so the caller must still see this
        // as a success. The scheduler's regular Job 2 run will pick up the new index either way.
        try {
            AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);
            checkMarketOpen(userAlpacaAPI);

            List<Position> positions = fetchPositions(userAlpacaAPI, userId);
            Map<String, Position> positionsBySymbol = positions.stream()
                    .collect(Collectors.toMap(Position::getSymbol, p -> p));
            Set<String> allHeld = new HashSet<>(positionsBySymbol.keySet());

            log.info("Index switch for user {}: selling all {} current holdings before buying {}",
                    userId, allHeld.size(), newIndex);
            sold = sellSymbols(userAlpacaAPI, user, allHeld, positionsBySymbol, previousIndex);

            List<DailyRecommendation> newTop5 =
                    dailyRecommendationRepository.findByFilterNameOrderByMomentumScoreDesc(newIndex);
            Set<String> newSymbols =
                    newTop5.stream().map(DailyRecommendation::getSymbol).collect(Collectors.toSet());

            log.info("Index switch for user {}: buying top {} for {}", userId, newSymbols.size(), newIndex);
            bought = buySymbols(userAlpacaAPI, user, newSymbols, newSymbols.size(), newIndex);
        } catch (MarketClosedException e) {
            marketWasOpen = false;
            log.info("Index switch for user {}: preference saved as {} — market closed, trading "
                    + "deferred to the next scheduled run", userId, newIndex);
        } catch (Exception e) {
            log.error("Index switch for user {}: preference saved as {}, but the immediate rebalance "
                    + "failed: {}", userId, newIndex, e.getMessage(), e);
        }

        emailService.sendIndexSwitchEmail(user, previousIndex, newIndex, user.getInvestmentAmount(),
                sold, bought, marketWasOpen, LocalDateTime.now(ZoneOffset.UTC));
    }

    private List<DailyTrade> sellSymbols(AlpacaAPI userAlpacaAPI, User user, Set<String> symbols,
                                          Map<String, Position> positionsBySymbol, String indexFilter) {
        return symbols.parallelStream().map(symbol -> {
            Position position = positionsBySymbol.get(symbol);
            if (position == null) {
                return null;
            }
            BigDecimal intendedQuantity = new BigDecimal(position.getQuantity());
            try {
                Order order = userAlpacaAPI.orders().requestOrder(
                        symbol, intendedQuantity.doubleValue(), null, OrderSide.SELL,
                        OrderType.MARKET, OrderTimeInForce.DAY,
                        null, null, null, null, null, null, null, null, null, null);

                Order filledOrder = waitForFill(userAlpacaAPI, order.getId());
                if (filledOrder != null) {
                    BigDecimal filledPrice = parseOrZero(filledOrder.getAverageFillPrice());
                    BigDecimal filledQty = parseOrZero(filledOrder.getFilledQuantity());
                    BigDecimal amountReceived = filledPrice.multiply(filledQty);
                    return saveTrade(user, symbol, ActionType.SELL, TradeStatus.FILLED, amountReceived, filledPrice,
                            filledQty, order.getId(), indexFilter);
                } else {
                    // Alpaca accepted the order but never confirmed a fill within the wait window.
                    // Record what's actually known — the order exists, and exactly how many shares
                    // we told it to sell — rather than a misleading $0 / 0-share "fill".
                    log.warn("Daily trading: sell for {} (user {}) not confirmed filled within the "
                            + "wait window — recording as PENDING", symbol, user.getId());
                    return saveTrade(user, symbol, ActionType.SELL, TradeStatus.PENDING, null, null, intendedQuantity,
                            order.getId(), indexFilter);
                }
            } catch (Exception e) {
                log.warn("Daily trading: sell failed for {} (user {}): {}", symbol, user.getId(), e.getMessage());
                return saveTrade(user, symbol, ActionType.SELL, TradeStatus.FAILED, null, null, intendedQuantity, null,
                        indexFilter);
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private static final BigDecimal BUFFER_RATE = new BigDecimal("0.10");
    private static final BigDecimal MIN_ORDER_SIZE = BigDecimal.ONE;

    // targetAllocationCount is the size of the FULL top-5 set for this rebalance, not symbols.size()
    // (which is just the newly-entering subset being bought here). Every one of the 5 target
    // stocks — new or already held — must get exactly 1/targetAllocationCount of the safe amount;
    // sizing off symbols.size() instead would dump most of the day's investment amount into
    // whichever handful of stocks happen to be new, since already-held stocks that stayed in the
    // top 5 aren't touched by this call at all.
    private List<DailyTrade> buySymbols(AlpacaAPI userAlpacaAPI, User user, Set<String> symbols,
                                         int targetAllocationCount, String indexFilter) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        if (targetAllocationCount <= 0) {
            log.warn("Daily trading: skipping buys for user {} — invalid target allocation count {}",
                    user.getId(), targetAllocationCount);
            return List.of();
        }

        // investment_amount is the user-set amount to invest per rebalance cycle (set once during
        // onboarding/settings — see UserController). No investment_amount means no sizing decision
        // can be made, so auto-trading is skipped entirely rather than guessing an amount.
        BigDecimal investmentAmount = user.getInvestmentAmount();
        if (investmentAmount == null) {
            log.info("Daily trading: skipping buys for user {} — no investment_amount set", user.getId());
            return List.of();
        }

        // Buffer is a safety margin only: never stored, never deducted from anything, never
        // reserved on Alpaca's side. It only shrinks the amount we're willing to *attempt* to
        // invest this run — the cash always stays in the user's account either way.
        BigDecimal buffer = investmentAmount.multiply(BUFFER_RATE);
        BigDecimal safeAmount = investmentAmount.subtract(buffer);

        BigDecimal availableBuyingPower;
        try {
            Account account = userAlpacaAPI.account().get();
            availableBuyingPower = new BigDecimal(account.getBuyingPower());
        } catch (AlpacaClientException e) {
            log.warn("Daily trading: failed to fetch buying power for user {}: {}", user.getId(), e.getMessage());
            return List.of();
        }

        if (availableBuyingPower.compareTo(safeAmount) < 0) {
            log.warn("Insufficient buying power after buffer deduction for user {}", user.getId());
            return List.of();
        }

        BigDecimal amountPerStock = safeAmount.divide(BigDecimal.valueOf(targetAllocationCount), 2, RoundingMode.DOWN);
        if (amountPerStock.compareTo(MIN_ORDER_SIZE) < 0) {
            log.warn("Investment amount too small after buffer deduction for user {}", user.getId());
            return List.of();
        }

        return symbols.parallelStream().map(symbol -> {
            try {
                Order order = userAlpacaAPI.orders().requestOrder(
                        symbol, null, amountPerStock.doubleValue(), OrderSide.BUY,
                        OrderType.MARKET, OrderTimeInForce.DAY,
                        null, null, null, null, null, null, null, null, null, null);

                Order filledOrder = waitForFill(userAlpacaAPI, order.getId());
                if (filledOrder != null) {
                    BigDecimal filledPrice = parseOrZero(filledOrder.getAverageFillPrice());
                    BigDecimal filledQty = parseOrZero(filledOrder.getFilledQuantity());
                    return saveTrade(user, symbol, ActionType.BUY, TradeStatus.FILLED, amountPerStock, filledPrice,
                            filledQty, order.getId(), indexFilter);
                } else {
                    // The dollar amount we asked Alpaca to buy is known regardless of fill status —
                    // it's a notional order, so share count and price genuinely aren't determinable
                    // until it fills. Never fabricate a $0 / 0-share result for what's still pending.
                    log.warn("Daily trading: buy for {} (user {}) not confirmed filled within the "
                            + "wait window — recording as PENDING", symbol, user.getId());
                    return saveTrade(user, symbol, ActionType.BUY, TradeStatus.PENDING, amountPerStock, null, null,
                            order.getId(), indexFilter);
                }
            } catch (Exception e) {
                log.warn("Daily trading: buy failed for {} (user {}): {}", symbol, user.getId(), e.getMessage());
                return saveTrade(user, symbol, ActionType.BUY, TradeStatus.FAILED, amountPerStock, null, null, null,
                        indexFilter);
            }
        }).collect(Collectors.toList());
    }

    private DailyTrade saveTrade(User user, String symbol, ActionType action, TradeStatus status, BigDecimal amount,
                                  BigDecimal pricePerShare, BigDecimal quantity, String alpacaOrderId,
                                  String indexFilter) {
        DailyTrade trade = new DailyTrade(null, user, symbol, action, status, amount, pricePerShare, quantity,
                alpacaOrderId, indexFilter, null);
        DailyTrade saved = dailyTradeRepository.save(trade);

        if (status == TradeStatus.FAILED) {
            emailService.sendTradeFailedEmail(user, symbol, action, LocalDateTime.now(ZoneOffset.UTC));
        }

        return saved;
    }

    private BigDecimal parseOrZero(String value) {
        return (value != null && !value.isEmpty()) ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    private boolean hasApiKey(User user) {
        return user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank();
    }

    private AlpacaAPI buildUserAlpacaAPI(User user) {
        String apiKey = encryptionUtil.decrypt(user.getAlpacaApiKeyEncrypted());
        String apiSecret = encryptionUtil.decrypt(user.getAlpacaApiSecretEncrypted());
        return alpacaConfig.createUserAlpacaAPI(apiKey, apiSecret);
    }

    private List<Position> fetchPositions(AlpacaAPI userAlpacaAPI, Long userId) {
        try {
            return userAlpacaAPI.positions().get();
        } catch (AlpacaClientException e) {
            throw new RuntimeException("Failed to fetch Alpaca positions for user " + userId, e);
        }
    }

    // Best-effort — a failed fetch here shouldn't stop the rebalance email from going out, since
    // the trades themselves already succeeded; the email just shows "—" for portfolio value.
    private BigDecimal fetchPortfolioValue(AlpacaAPI userAlpacaAPI, Long userId) {
        try {
            Account account = userAlpacaAPI.account().get();
            return new BigDecimal(account.getPortfolioValue());
        } catch (Exception e) {
            log.warn("Daily trading: failed to fetch portfolio value for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void checkMarketOpen(AlpacaAPI alpacaAPI) {
        Clock clock;
        try {
            clock = alpacaAPI.clock().get();
        } catch (AlpacaClientException e) {
            throw new RuntimeException("Failed to fetch Alpaca market clock", e);
        }

        if (clock.getIsOpen() == null || !clock.getIsOpen()) {
            throw new MarketClosedException("Market is closed. It reopens at " + clock.getNextOpen() + ".");
        }
    }

    private Order waitForFill(AlpacaAPI alpacaAPI, String orderId) {
        for (int i = 0; i < 8; i++) {
            try {
                Thread.sleep(3000);
                Order order = alpacaAPI.orders().get(orderId, false);
                if (order != null && order.getAverageFillPrice() != null && !order.getAverageFillPrice().isEmpty()) {
                    return order;
                }
            } catch (Exception e) {
                log.warn("Retry {} waiting for fill on order {}", i + 1, orderId);
            }
        }
        return null;
    }
}
