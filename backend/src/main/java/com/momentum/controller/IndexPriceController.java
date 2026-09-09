package com.momentum.controller;

import com.momentum.service.IndexConstituentService;
import net.jacobpeterson.alpaca.AlpacaAPI;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.common.historical.bar.enums.BarTimePeriod;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.MultiStockBarsResponse;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.StockBar;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarAdjustment;
import net.jacobpeterson.alpaca.model.endpoint.marketdata.stock.historical.bar.enums.BarFeed;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real historical price data for the index a user tracks — not user-owned data, just public
 * market data, so this is a plain JWT-authenticated endpoint like /metrics and /recommendations/*
 * rather than a /:userId/... route with an ownership check.
 */
@RestController
public class IndexPriceController {

    private static final int HISTORY_DAYS = 30;

    // Each selectable index is tracked via its most liquid, widely-used ETF — real, tradable
    // proxies for the index itself, since Alpaca's market data endpoint serves equities/ETFs, not
    // raw index values.
    private static final Map<String, String> INDEX_TO_ETF = Map.of(
            IndexConstituentService.SP500, "SPY",
            IndexConstituentService.SP400, "MDY",
            IndexConstituentService.SP600, "SPSM",
            IndexConstituentService.NASDAQ100, "QQQ"
    );

    private final AlpacaAPI systemAlpacaAPI;

    public IndexPriceController(AlpacaAPI systemAlpacaAPI) {
        this.systemAlpacaAPI = systemAlpacaAPI;
    }

    @GetMapping("/index-price-history")
    public ResponseEntity<?> getIndexPriceHistory(@RequestParam String index) {
        String etfSymbol = INDEX_TO_ETF.get(index);
        if (etfSymbol == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Unknown index: " + index));
        }

        try {
            ZonedDateTime end = ZonedDateTime.now();
            ZonedDateTime start = end.minusDays(HISTORY_DAYS);

            // SPLIT (not RAW), same reasoning as DailyScoringService: an unadjusted series shows
            // an artificial cliff across any split in the window, misrepresenting the real trend.
            MultiStockBarsResponse response = systemAlpacaAPI.stockMarketData().getBars(
                    List.of(etfSymbol), start, end, 1000, null, 1,
                    BarTimePeriod.DAY, BarAdjustment.SPLIT, BarFeed.IEX
            );

            List<StockBar> bars = response.getBars() != null ? response.getBars().get(etfSymbol) : null;
            if (bars == null) {
                bars = List.of();
            }

            List<PricePoint> points = new ArrayList<>();
            for (StockBar bar : bars) {
                points.add(new PricePoint(bar.getTimestamp().toLocalDate(), BigDecimal.valueOf(bar.getClose())));
            }

            return ResponseEntity.ok(new IndexPriceHistoryResponse(index, etfSymbol, points));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch index price history: " + e.getMessage()));
        }
    }

    public record PricePoint(LocalDate date, BigDecimal close) {
    }

    public record IndexPriceHistoryResponse(String index, String etfSymbol, List<PricePoint> points) {
    }

    public record ErrorResponse(String error) {
    }
}
