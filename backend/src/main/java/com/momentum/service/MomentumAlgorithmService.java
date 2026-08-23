package com.momentum.service;

import com.momentum.model.Recommendation;
import com.momentum.model.Stock;
import com.momentum.model.StockPrice;
import com.momentum.model.enums.ActionType;
import com.momentum.repository.RecommendationRepository;
import com.momentum.repository.StockPriceRepository;
import com.momentum.repository.StockRepository;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.common.historical.bar.enums.BarTimePeriod;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.StockBar;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.StockBarsResponse;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarAdjustment;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MomentumAlgorithmService {

    private static final Logger log = LoggerFactory.getLogger(MomentumAlgorithmService.class);

    private static final MathContext MATH_CONTEXT = new MathContext(10);
    private static final int TOP_BOTTOM_COUNT = 10;

    private final AlpacaAPI alpacaAPI;
    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;
    private final RecommendationRepository recommendationRepository;

    public MomentumAlgorithmService(AlpacaAPI alpacaAPI,
                                     StockRepository stockRepository,
                                     StockPriceRepository stockPriceRepository,
                                     RecommendationRepository recommendationRepository) {
        this.alpacaAPI = alpacaAPI;
        this.stockRepository = stockRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.recommendationRepository = recommendationRepository;
    }

    public void generateWeeklyRecommendations() {
        List<Stock> stocks = stockRepository.findAll();
        List<ScoredStock> scoredStocks = new ArrayList<>();

        for (Stock stock : stocks) {
            try {
                BigDecimal momentumScore = calculateMomentumScore(stock);
                scoredStocks.add(new ScoredStock(stock, momentumScore));
            } catch (Exception e) {
                log.warn("Skipping stock {} due to error: {}", stock.getSymbol(), e.getMessage());
            }
        }

        Map<String, List<ScoredStock>> scoredStocksByIndex = scoredStocks.stream()
                .collect(Collectors.groupingBy(scored -> scored.stock().getIndexName()));

        LocalDate weekDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        for (Map.Entry<String, List<ScoredStock>> entry : scoredStocksByIndex.entrySet()) {
            String indexName = entry.getKey();
            List<ScoredStock> indexStocks = entry.getValue();

            indexStocks.sort((a, b) -> b.score().compareTo(a.score()));

            int total = indexStocks.size();
            for (int rank = 0; rank < total; rank++) {
                ScoredStock scored = indexStocks.get(rank);

                ActionType action;
                if (rank < TOP_BOTTOM_COUNT) {
                    action = ActionType.BUY;
                } else if (rank >= total - TOP_BOTTOM_COUNT) {
                    action = ActionType.SELL;
                } else {
                    action = ActionType.HOLD;
                }

                Recommendation recommendation = new Recommendation(
                        null,
                        scored.stock(),
                        scored.score(),
                        action,
                        indexName,
                        weekDate,
                        null
                );
                recommendationRepository.save(recommendation);
            }
        }
    }

    private BigDecimal calculateMomentumScore(Stock stock) throws Exception {
        ZonedDateTime end = ZonedDateTime.now();
        ZonedDateTime start = end.minusMonths(6);

        StockBarsResponse response = alpacaAPI.stockMarketData().getBars(
                stock.getSymbol(),
                start,
                end,
                10000,
                null,
                1,
                BarTimePeriod.DAY,
                BarAdjustment.RAW,
                BarFeed.IEX
        );

        List<StockBar> bars = response.getBars();
        if (bars == null || bars.size() < 2) {
            throw new IllegalStateException("Not enough price history for " + stock.getSymbol());
        }

        for (StockBar bar : bars) {
            StockPrice stockPrice = new StockPrice(
                    null,
                    stock,
                    BigDecimal.valueOf(bar.getClose()),
                    bar.getTimestamp().toLocalDate(),
                    null
            );
            stockPriceRepository.save(stockPrice);
        }

        BigDecimal latestPrice = BigDecimal.valueOf(bars.get(bars.size() - 1).getClose());
        BigDecimal price6mAgo = BigDecimal.valueOf(bars.get(0).getClose());
        BigDecimal price3mAgo = findPriceOnOrAfter(bars, LocalDate.now().minusMonths(3));
        BigDecimal price1mAgo = findPriceOnOrAfter(bars, LocalDate.now().minusMonths(1));

        BigDecimal ret6m = calculateReturn(latestPrice, price6mAgo);
        BigDecimal ret3m = calculateReturn(latestPrice, price3mAgo);
        BigDecimal ret1m = calculateReturn(latestPrice, price1mAgo);
        BigDecimal vol3m = calculateVolatility3m(bars);

        return ret6m.multiply(new BigDecimal("0.5"))
                .add(ret3m.multiply(new BigDecimal("0.3")))
                .add(ret1m.multiply(new BigDecimal("0.2")))
                .subtract(vol3m.multiply(new BigDecimal("0.1")))
                .setScale(6, RoundingMode.HALF_UP);
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

    private record ScoredStock(Stock stock, BigDecimal score) {
    }
}
