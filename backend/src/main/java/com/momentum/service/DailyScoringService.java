package com.momentum.service;

import com.momentum.model.DailyRecommendation;
import com.momentum.model.SchedulerState;
import com.momentum.model.User;
import com.momentum.repository.DailyRecommendationRepository;
import com.momentum.repository.SchedulerStateRepository;
import com.momentum.repository.UserRepository;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.assets.Asset;
import net.jacobpeterson.alpaca.model.endpoint.assets.enums.AssetClass;
import net.jacobpeterson.alpaca.model.endpoint.assets.enums.AssetStatus;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.common.historical.bar.enums.BarTimePeriod;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.MultiStockBarsResponse;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.StockBar;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarAdjustment;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Job 1 of the daily engine. Scores the entire live Alpaca US-equity universe in memory — nothing
 * about the universe or the full score list is ever persisted (see plan: "the only DB write the
 * algorithm needs to do" is the top-5-per-filter result). Index membership from
 * {@link IndexConstituentService}'s output is used only to filter the in-memory scores, never as
 * a data source.
 */
@Service
public class DailyScoringService {

    private static final Logger log = LoggerFactory.getLogger(DailyScoringService.class);
    private static final MathContext MATH_CONTEXT = new MathContext(10);
    private static final int TOP_N = 5;
    private static final int SYMBOLS_PER_BATCH = 200;
    private static final double MINIMUM_SCORED_FRACTION = 0.9;
    private static final int MINIMUM_HISTORY_MONTHS = 3;

    public static final String FULL_MARKET = "FULL_MARKET";

    private static final Long SCHEDULER_STATE_ID = 1L;

    private final AlpacaAPI systemAlpacaAPI;
    private final IndexConstituentService indexConstituentService;
    private final DailyRecommendationRepository dailyRecommendationRepository;
    private final MetricsService metricsService;
    private final SchedulerStateRepository schedulerStateRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final TransactionTemplate transactionTemplate;

    public DailyScoringService(AlpacaAPI systemAlpacaAPI,
                                IndexConstituentService indexConstituentService,
                                DailyRecommendationRepository dailyRecommendationRepository,
                                MetricsService metricsService,
                                SchedulerStateRepository schedulerStateRepository,
                                UserRepository userRepository,
                                EmailService emailService,
                                PlatformTransactionManager transactionManager) {
        this.systemAlpacaAPI = systemAlpacaAPI;
        this.indexConstituentService = indexConstituentService;
        this.dailyRecommendationRepository = dailyRecommendationRepository;
        this.metricsService = metricsService;
        this.schedulerStateRepository = schedulerStateRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        // A plain TransactionTemplate rather than @Transactional: this class calls the
        // delete+save block on itself (self-invocation), which Spring's proxy-based @Transactional
        // would silently ignore. TransactionTemplate wraps just those two calls — not the whole
        // scoring run — so the DB connection isn't held for the tens of seconds the Alpaca calls
        // above it can take.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void runDailyScoring() throws Exception {
        long startTime = System.currentTimeMillis();
        metricsService.recordRunStart();

        try {
            // Universe = union of the 4 index constituent lists, not the entire US equity market.
            // Cuts ~13,400 candidates down to ~1,400 — this is what's actually slow/unpredictable
            // (fetching 6mo of bars per symbol), and users only ever see top-5-per-index results
            // anyway, so scoring the other ~12,000 names never bought anything.
            Set<String> universe = new HashSet<>();
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.SP500));
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.NASDAQ100));
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.SP400));
            universe.addAll(indexConstituentService.getConstituents(IndexConstituentService.SP600));

            // Still fetch the full Alpaca asset list — it's a single fast metadata call (not the
            // slow part), used here only to get real company names and confirm each ticker is
            // still actually tradable, then filtered down to the index universe above.
            List<Asset> assets = systemAlpacaAPI.assets().get(AssetStatus.ACTIVE, AssetClass.US_EQUITY);

            Map<String, String> namesBySymbol = new HashMap<>();
            List<String> symbols = new ArrayList<>();
            for (Asset asset : assets) {
                if (asset.getSymbol() == null || Boolean.FALSE.equals(asset.getTradable())
                        || !universe.contains(asset.getSymbol())) {
                    continue;
                }
                symbols.add(asset.getSymbol());
                namesBySymbol.put(asset.getSymbol(), asset.getName() != null ? asset.getName() : asset.getSymbol());
            }

            log.info("Daily scoring: {} unique index-constituent tickers to score (union of {} S&P 500, "
                            + "{} Nasdaq 100, {} S&P 400, {} S&P 600)", symbols.size(),
                    indexConstituentService.getConstituents(IndexConstituentService.SP500).size(),
                    indexConstituentService.getConstituents(IndexConstituentService.NASDAQ100).size(),
                    indexConstituentService.getConstituents(IndexConstituentService.SP400).size(),
                    indexConstituentService.getConstituents(IndexConstituentService.SP600).size());

            List<ScoredStock> scored = scoreAll(symbols);

            log.info("Daily scoring: {} of {} symbols produced a valid score", scored.size(), symbols.size());

            // A degraded run (e.g. widespread Alpaca batch timeouts) must never be silently treated
            // as healthy just because some data came back — that would let a top 5 computed from a
            // fraction of the real universe pass as indistinguishable from a normal run. Below this
            // threshold, the whole run is rejected rather than trusted.
            if (!symbols.isEmpty() && (double) scored.size() / symbols.size() < MINIMUM_SCORED_FRACTION) {
                long durationMs = System.currentTimeMillis() - startTime;
                String message = String.format(
                        "Scoring degraded — only %d of %d stocks scored. Keeping previous recommendations.",
                        scored.size(), symbols.size());
                log.warn(message);
                metricsService.recordRunFailure(message, durationMs);
                return;
            }

            List<DailyRecommendation> fullMarket = topN(scored, FULL_MARKET, namesBySymbol);
            List<DailyRecommendation> sp500 = topNForIndex(scored, IndexConstituentService.SP500, namesBySymbol);
            List<DailyRecommendation> nasdaq100 =
                    topNForIndex(scored, IndexConstituentService.NASDAQ100, namesBySymbol);
            List<DailyRecommendation> sp400 = topNForIndex(scored, IndexConstituentService.SP400, namesBySymbol);
            List<DailyRecommendation> sp600 = topNForIndex(scored, IndexConstituentService.SP600, namesBySymbol);

            long durationMs = System.currentTimeMillis() - startTime;

            // Only replace stored recommendations if every filter produced at least one result.
            // A partial or total failure (e.g. Alpaca timeouts across the board) must never wipe
            // yesterday's good data and leave nothing in its place.
            if (fullMarket.isEmpty() || sp500.isEmpty() || nasdaq100.isEmpty() || sp400.isEmpty() || sp600.isEmpty()) {
                log.warn("Scoring produced no results — keeping previous recommendations.");
                metricsService.recordRunFailure("Scoring produced no results — keeping previous recommendations",
                        durationMs);
                return;
            }

            List<DailyRecommendation> toSave = new ArrayList<>();
            toSave.addAll(fullMarket);
            toSave.addAll(sp500);
            toSave.addAll(nasdaq100);
            toSave.addAll(sp400);
            toSave.addAll(sp600);

            // Atomic: deleteAll() and saveAll() either both commit or neither does. Without this,
            // a crash, dropped connection, or thrown exception between the two calls would leave
            // daily_recommendation genuinely empty — not "yesterday's data kept" — since each
            // repository method is otherwise its own separate auto-committed transaction.
            transactionTemplate.executeWithoutResult(status -> {
                dailyRecommendationRepository.deleteAll();
                dailyRecommendationRepository.saveAll(toSave);
            });

            metricsService.recordRunSuccess(scored.size(), durationMs);
            persistRunStats(scored.size(), durationMs);
            log.info("Daily scoring complete in {}ms, {} recommendation rows stored", durationMs, toSave.size());

            Map<String, List<DailyRecommendation>> byFilter = Map.of(
                    IndexConstituentService.SP500, sp500,
                    IndexConstituentService.NASDAQ100, nasdaq100,
                    IndexConstituentService.SP400, sp400,
                    IndexConstituentService.SP600, sp600,
                    FULL_MARKET, fullMarket
            );
            sendTopFiveEmails(byFilter);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            metricsService.recordRunFailure(e.getMessage(), durationMs);
            throw e;
        }
    }

    // MetricsService's own copy of these is in-memory only and gets wiped on every restart —
    // persisting them here too is what lets MetricsController's /metrics fallback show real
    // values instead of "—" after a restart, as long as a scoring run has succeeded at least
    // once. Same fetch-mutate-save upsert pattern DailyEngineSchedulerService uses on this same
    // row for the job1/job2 date fields, so the two writers can't clobber each other's columns.
    private void persistRunStats(int stocksScored, long durationMs) {
        SchedulerState state = schedulerStateRepository.findById(SCHEDULER_STATE_ID)
                .orElseGet(() -> new SchedulerState(SCHEDULER_STATE_ID, null, null, null, null, null));
        state.setLastRunStocksScored(stocksScored);
        state.setLastRunDurationMs(durationMs);
        schedulerStateRepository.save(state);
    }

    // Best-effort — a mail failure for one user must never affect scoring, and never block the
    // next user's email either (see EmailService.send's own try/catch).
    private void sendTopFiveEmails(Map<String, List<DailyRecommendation>> byFilter) {
        LocalDateTime scoredAt = LocalDateTime.now(ZoneOffset.UTC);

        List<User> eligibleUsers = userRepository.findAll().stream()
                .filter(u -> u.getAlpacaApiKeyEncrypted() != null && !u.getAlpacaApiKeyEncrypted().isBlank())
                .filter(u -> u.getSelectedIndex() != null && !u.getSelectedIndex().isBlank())
                .toList();

        for (User user : eligibleUsers) {
            List<DailyRecommendation> top5 = byFilter.get(user.getSelectedIndex());
            if (top5 == null || top5.isEmpty()) {
                continue;
            }
            emailService.sendTopFiveEmail(user, user.getSelectedIndex(), top5, scoredAt);
        }
    }

    private List<ScoredStock> scoreAll(List<String> symbols) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += SYMBOLS_PER_BATCH) {
            batches.add(symbols.subList(i, Math.min(i + SYMBOLS_PER_BATCH, symbols.size())));
        }

        List<BatchResult> batchResults = batches.parallelStream()
                .map(this::scoreBatch)
                .collect(Collectors.toList());

        int totalSkippedForHistory = batchResults.stream().mapToInt(BatchResult::skippedForHistory).sum();
        log.info("Daily scoring: {} stocks skipped for insufficient history (less than {} months)",
                totalSkippedForHistory, MINIMUM_HISTORY_MONTHS);

        return batchResults.stream().flatMap(r -> r.scored().stream()).collect(Collectors.toList());
    }

    private BatchResult scoreBatch(List<String> batch) {
        try {
            Map<String, List<StockBar>> barsBySymbol = fetchBarsForBatch(batch);
            List<ScoredStock> results = new ArrayList<>();
            int skippedForHistory = 0;

            for (Map.Entry<String, List<StockBar>> entry : barsBySymbol.entrySet()) {
                List<StockBar> bars = entry.getValue();

                // A stock with less than 3 months of real history gets treated by
                // calculateMomentumScore as if its earliest available bar were "6 months ago,"
                // wildly overstating its return over such a short real window. Skip it outright
                // rather than mis-score it.
                if (!hasMinimumHistory(bars)) {
                    skippedForHistory++;
                    continue;
                }

                try {
                    MomentumComponents components = calculateMomentumScore(bars);
                    if (components != null) {
                        results.add(new ScoredStock(entry.getKey(), components.ret6m(), components.ret3m(),
                                components.ret1m(), components.vol3m(), components.score()));
                    }
                } catch (Exception e) {
                    log.warn("Skipping stock {}: {}", entry.getKey(), e.getMessage());
                }
            }

            log.info("Daily scoring: batch of {} symbols done ({} scored, {} skipped for insufficient history)",
                    batch.size(), results.size(), skippedForHistory);
            return new BatchResult(results, skippedForHistory);
        } catch (Exception e) {
            log.warn("Daily scoring: batch fetch failed for {} symbols starting with {}: {}",
                    batch.size(), batch.get(0), e.getMessage());
            return new BatchResult(List.of(), 0);
        }
    }

    private boolean hasMinimumHistory(List<StockBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return false;
        }
        LocalDate earliestBarDate = bars.get(0).getTimestamp().toLocalDate();
        LocalDate minimumRequiredStart = LocalDate.now().minusMonths(MINIMUM_HISTORY_MONTHS);
        return !earliestBarDate.isAfter(minimumRequiredStart);
    }

    private Map<String, List<StockBar>> fetchBarsForBatch(List<String> batch) throws Exception {
        ZonedDateTime end = ZonedDateTime.now();
        ZonedDateTime start = end.minusMonths(6);

        Map<String, List<StockBar>> allBars = new HashMap<>();
        String pageToken = null;

        do {
            // SPLIT (not RAW): an unadjusted price series shows an artificial cliff across a stock
            // split — the momentum formula would read that cliff as a huge real return/volatility
            // spike, not the computation artifact it actually is.
            MultiStockBarsResponse response = systemAlpacaAPI.stockMarketData().getBars(
                    batch, start, end, 10000, pageToken, 1,
                    BarTimePeriod.DAY, BarAdjustment.SPLIT, BarFeed.IEX
            );

            if (response.getBars() != null) {
                response.getBars().forEach((symbol, bars) ->
                        allBars.computeIfAbsent(symbol, s -> new ArrayList<>()).addAll(bars));
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null);

        return allBars;
    }

    private MomentumComponents calculateMomentumScore(List<StockBar> bars) {
        if (bars == null || bars.size() < 2) {
            return null;
        }

        BigDecimal latestPrice = BigDecimal.valueOf(bars.get(bars.size() - 1).getClose());
        BigDecimal price6mAgo = BigDecimal.valueOf(bars.get(0).getClose());
        BigDecimal price3mAgo = findPriceOnOrAfter(bars, LocalDate.now().minusMonths(3));
        BigDecimal price1mAgo = findPriceOnOrAfter(bars, LocalDate.now().minusMonths(1));

        BigDecimal ret6m = calculateReturn(latestPrice, price6mAgo);
        BigDecimal ret3m = calculateReturn(latestPrice, price3mAgo);
        BigDecimal ret1m = calculateReturn(latestPrice, price1mAgo);
        BigDecimal vol3m = calculateVolatility3m(bars);

        BigDecimal score = ret6m.multiply(new BigDecimal("0.5"))
                .add(ret3m.multiply(new BigDecimal("0.3")))
                .add(ret1m.multiply(new BigDecimal("0.2")))
                .subtract(vol3m.multiply(new BigDecimal("0.1")))
                .setScale(6, RoundingMode.HALF_UP);

        // Rounded only for display/storage — score above is computed from the full-precision
        // values, never from these rounded ones, so persisting the breakdown can't introduce
        // drift into the ranking math itself (hand-verified against raw Alpaca data elsewhere).
        return new MomentumComponents(
                ret6m.setScale(6, RoundingMode.HALF_UP),
                ret3m.setScale(6, RoundingMode.HALF_UP),
                ret1m.setScale(6, RoundingMode.HALF_UP),
                vol3m.setScale(6, RoundingMode.HALF_UP),
                score
        );
    }

    private BigDecimal calculateReturn(BigDecimal latestPrice, BigDecimal pastPrice) {
        return latestPrice.subtract(pastPrice).divide(pastPrice, MATH_CONTEXT);
    }

    private BigDecimal findPriceOnOrAfter(List<StockBar> bars, LocalDate targetDate) {
        for (StockBar bar : bars) {
            if (!bar.getTimestamp().toLocalDate().isBefore(targetDate)) {
                return BigDecimal.valueOf(bar.getClose());
            }
        }
        return BigDecimal.valueOf(bars.get(bars.size() - 1).getClose());
    }

    private BigDecimal calculateVolatility3m(List<StockBar> bars) {
        LocalDate cutoff = LocalDate.now().minusMonths(3);

        List<BigDecimal> closesInWindow = bars.stream()
                .filter(bar -> !bar.getTimestamp().toLocalDate().isBefore(cutoff))
                .map(bar -> BigDecimal.valueOf(bar.getClose()))
                .collect(Collectors.toList());

        if (closesInWindow.size() < 2) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> dailyReturns = new ArrayList<>();
        for (int i = 1; i < closesInWindow.size(); i++) {
            BigDecimal previousClose = closesInWindow.get(i - 1);
            BigDecimal currentClose = closesInWindow.get(i);
            dailyReturns.add(currentClose.subtract(previousClose).divide(previousClose, MATH_CONTEXT));
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal dailyReturn : dailyReturns) {
            sum = sum.add(dailyReturn);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(dailyReturns.size()), MATH_CONTEXT);

        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (BigDecimal dailyReturn : dailyReturns) {
            BigDecimal diff = dailyReturn.subtract(mean);
            sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
        }

        int sampleSize = dailyReturns.size();
        BigDecimal divisor = BigDecimal.valueOf(sampleSize > 1 ? sampleSize - 1 : 1);
        BigDecimal variance = sumSquaredDiff.divide(divisor, MATH_CONTEXT);

        return variance.sqrt(MATH_CONTEXT);
    }

    private List<DailyRecommendation> topNForIndex(List<ScoredStock> scored, String indexName,
                                                     Map<String, String> namesBySymbol) {
        Set<String> constituents = indexConstituentService.getConstituents(indexName);

        if (constituents.isEmpty()) {
            log.warn("No index constituents loaded for {} — skipping", indexName);
            return List.of();
        }

        List<ScoredStock> filtered = scored.stream()
                .filter(s -> constituents.contains(s.symbol()))
                .collect(Collectors.toList());

        return topN(filtered, indexName, namesBySymbol);
    }

    private List<DailyRecommendation> topN(List<ScoredStock> pool, String filterName,
                                            Map<String, String> namesBySymbol) {
        return pool.stream()
                .sorted(Comparator.comparing(ScoredStock::score).reversed())
                .limit(TOP_N)
                .map(s -> new DailyRecommendation(null, filterName, s.symbol(),
                        namesBySymbol.getOrDefault(s.symbol(), s.symbol()), s.score(),
                        s.ret6m(), s.ret3m(), s.ret1m(), s.vol3m(), null))
                .collect(Collectors.toList());
    }

    private record ScoredStock(String symbol, BigDecimal ret6m, BigDecimal ret3m, BigDecimal ret1m,
                                BigDecimal vol3m, BigDecimal score) {
    }

    private record MomentumComponents(BigDecimal ret6m, BigDecimal ret3m, BigDecimal ret1m,
                                       BigDecimal vol3m, BigDecimal score) {
    }

    private record BatchResult(List<ScoredStock> scored, int skippedForHistory) {
    }
}
