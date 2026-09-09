package com.momentum.controller;

import com.momentum.config.AlpacaConfig;
import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import com.momentum.service.IndexConstituentService;
import com.momentum.service.UserAuthorizationService;
import com.momentum.util.EncryptionUtil;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.account.Account;
import net.jacobpeterson.alpaca.model.endpoint.assets.Asset;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.common.historical.bar.enums.BarTimePeriod;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.MultiStockBarsResponse;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.StockBar;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarAdjustment;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarFeed;
import net.jacobpeterson.alpaca.model.endpoint.portfoliohistory.PortfolioHistory;
import net.jacobpeterson.alpaca.model.endpoint.portfoliohistory.PortfolioHistoryDataPoint;
import net.jacobpeterson.alpaca.model.endpoint.portfoliohistory.enums.PortfolioPeriodUnit;
import net.jacobpeterson.alpaca.model.endpoint.portfoliohistory.enums.PortfolioTimeFrame;
import net.jacobpeterson.alpaca.model.endpoint.positions.Position;
import net.jacobpeterson.alpaca.rest.AlpacaClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
public class AccountController {

    private final UserRepository userRepository;
    private final AlpacaConfig alpacaConfig;
    private final EncryptionUtil encryptionUtil;
    private final UserAuthorizationService userAuthorizationService;
    private final AlpacaAPI systemAlpacaAPI;

    public AccountController(UserRepository userRepository,
                              AlpacaConfig alpacaConfig,
                              EncryptionUtil encryptionUtil,
                              UserAuthorizationService userAuthorizationService,
                              AlpacaAPI systemAlpacaAPI) {
        this.userRepository = userRepository;
        this.alpacaConfig = alpacaConfig;
        this.encryptionUtil = encryptionUtil;
        this.userAuthorizationService = userAuthorizationService;
        this.systemAlpacaAPI = systemAlpacaAPI;
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
        if (!hasAlpacaKey(user)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Connect your Alpaca account in Settings first."));
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
        if (!hasAlpacaKey(user)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Connect your Alpaca account in Settings first."));
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

    // Portfolio performance vs. the tracked index over the same real window — sourced from
    // Alpaca's own Portfolio History endpoint rather than derived from our trade log, since a
    // rebalancing account's cost basis shifts constantly and Alpaca already computes the correct
    // time-weighted return. 1 year is comfortably longer than this project has existed, so in
    // practice this always covers the account's full history ("since start").
    @GetMapping("/{userId}/benchmark")
    public ResponseEntity<?> getBenchmark(@PathVariable Long userId) {
        if (!userAuthorizationService.isOwnedByCaller(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("You do not have access to this account"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (!hasAlpacaKey(user)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Connect your Alpaca account in Settings first."));
        }

        AlpacaAPI userAlpacaAPI = buildUserAlpacaAPI(user);

        try {
            PortfolioHistory history = userAlpacaAPI.portfolioHistory()
                    .get(1, PortfolioPeriodUnit.YEAR, PortfolioTimeFrame.ONE_DAY, null, false);

            List<PortfolioHistoryDataPoint> validPoints = history.getDataPoints() == null
                    ? List.of()
                    : history.getDataPoints().stream()
                            .filter(p -> p.getEquity() != null && p.getProfitLossPercent() != null)
                            .toList();

            if (validPoints.size() < 2) {
                return ResponseEntity.ok(new BenchmarkResponse(
                        user.getSelectedIndex(), null, null, null, null));
            }

            LocalDate periodStart = validPoints.get(0).getTimestamp().toLocalDate();
            BigDecimal portfolioReturnPercent = BigDecimal.valueOf(
                    validPoints.get(validPoints.size() - 1).getProfitLossPercent());

            String etfSymbol = IndexConstituentService.INDEX_TO_ETF.get(user.getSelectedIndex());
            BigDecimal indexReturnPercent = etfSymbol == null
                    ? null
                    : fetchIndexReturnPercent(etfSymbol, periodStart);

            return ResponseEntity.ok(new BenchmarkResponse(
                    user.getSelectedIndex(), etfSymbol, periodStart, portfolioReturnPercent, indexReturnPercent));
        } catch (AlpacaClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch portfolio history from Alpaca: " + e.getMessage()));
        }
    }

    // Best-effort: a failure here shouldn't fail the whole benchmark response, since the
    // portfolio-side numbers are already known good — just omit the index comparison.
    private BigDecimal fetchIndexReturnPercent(String etfSymbol, LocalDate periodStart) {
        try {
            ZonedDateTime start = periodStart.atStartOfDay(ZonedDateTime.now().getZone());
            ZonedDateTime end = ZonedDateTime.now();

            MultiStockBarsResponse response = systemAlpacaAPI.stockMarketData().getBars(
                    List.of(etfSymbol), start, end, 1000, null, 1,
                    BarTimePeriod.DAY, BarAdjustment.SPLIT, BarFeed.IEX
            );

            List<StockBar> bars = response.getBars() != null ? response.getBars().get(etfSymbol) : null;
            if (bars == null || bars.size() < 2) {
                return null;
            }

            double firstClose = bars.get(0).getClose();
            double lastClose = bars.get(bars.size() - 1).getClose();
            return BigDecimal.valueOf((lastClose - firstClose) / firstClose);
        } catch (Exception e) {
            return null;
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

    // Every endpoint in this controller builds a user-specific AlpacaAPI client from the user's
    // saved key — with no key, the Alpaca SDK's constructor throws an uncaught
    // IllegalArgumentException (crashing the request) rather than something callers can handle.
    // This guard turns "no key yet" into a clean, expected 400 instead.
    private boolean hasAlpacaKey(User user) {
        return user.getAlpacaApiKeyEncrypted() != null && !user.getAlpacaApiKeyEncrypted().isBlank();
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

    // indexSymbol/indexReturnPercent are null when the user hasn't selected a benchmarkable index
    // (no index chosen yet, or FULL_MARKET, which has no single ETF proxy) or when there isn't
    // yet enough portfolio history to compute a return.
    public record BenchmarkResponse(String selectedIndex, String indexSymbol, LocalDate periodStart,
                                     BigDecimal portfolioReturnPercent, BigDecimal indexReturnPercent) {
    }

    public record ErrorResponse(String error) {
    }
}
