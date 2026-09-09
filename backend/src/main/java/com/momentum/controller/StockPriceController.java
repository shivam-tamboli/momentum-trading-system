package com.momentum.controller;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Short-window price history for arbitrary stock symbols — used to render sparklines next to
 * each recommendation's momentum score. Public market data, same JWT-only auth as
 * {@link IndexPriceController} rather than a per-user /:userId/... route.
 */
@RestController
public class StockPriceController {

    private static final int HISTORY_DAYS = 14;
    private static final int MAX_SYMBOLS = 10;

    private final AlpacaAPI systemAlpacaAPI;

    public StockPriceController(AlpacaAPI systemAlpacaAPI) {
        this.systemAlpacaAPI = systemAlpacaAPI;
    }

    @GetMapping("/stock-price-history")
    public ResponseEntity<?> getStockPriceHistory(@RequestParam String symbols) {
        List<String> symbolList = List.of(symbols.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        if (symbolList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("symbols must contain at least one ticker"));
        }
        if (symbolList.size() > MAX_SYMBOLS) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("symbols must contain at most " + MAX_SYMBOLS + " tickers"));
        }

        try {
            ZonedDateTime end = ZonedDateTime.now();
            ZonedDateTime start = end.minusDays(HISTORY_DAYS);

            MultiStockBarsResponse response = systemAlpacaAPI.stockMarketData().getBars(
                    symbolList, start, end, 1000, null, 1,
                    BarTimePeriod.DAY, BarAdjustment.SPLIT, BarFeed.IEX
            );

            Map<String, List<PricePoint>> result = new LinkedHashMap<>();
            for (String symbol : symbolList) {
                List<StockBar> bars = response.getBars() != null ? response.getBars().get(symbol) : null;
                List<PricePoint> points = new ArrayList<>();
                if (bars != null) {
                    for (StockBar bar : bars) {
                        points.add(new PricePoint(bar.getTimestamp().toLocalDate(), BigDecimal.valueOf(bar.getClose())));
                    }
                }
                result.put(symbol, points);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("Failed to fetch stock price history: " + e.getMessage()));
        }
    }

    public record PricePoint(LocalDate date, BigDecimal close) {
    }

    public record ErrorResponse(String error) {
    }
}
