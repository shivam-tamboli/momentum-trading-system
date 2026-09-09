package com.momentum.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Index membership is a pure filter list, held in memory only — no DB table. Sourced from 4
 * plain-text files committed to this repo under {@code index-constituents/} on the classpath,
 * kept up to date by a daily GitHub Actions workflow ({@code update-index-constituents.yml})
 * that runs {@code scripts/update_index_constituents.py} and opens a PR for review — never by
 * the running application itself.
 *
 * This is deliberate: the app used to fetch these lists over HTTP from third-party GitHub repos
 * at startup and daily thereafter. One of those (Nasdaq 100) went 224 days without a single
 * commit before we noticed. Vendoring the data — committed to our own repo, updated on our own
 * schedule, reviewed via PR before it can affect a live deploy — means the running app has zero
 * runtime dependency on any external source ever being reachable. A stale or broken upstream now
 * shows up as a failed CI run, not a production incident.
 *
 * Never a data source for scoring — only used to filter DailyScoringService's already-computed
 * scores at read time.
 */
@Service
public class IndexConstituentService {

    private static final Logger log = LoggerFactory.getLogger(IndexConstituentService.class);

    private static final String RESOURCE_DIR = "index-constituents/";

    // A sanity floor per index, well below the real count — catches a corrupted or truncated
    // resource file at startup instead of silently running with a broken universe.
    private static final int SP500_MINIMUM = 450;
    private static final int NASDAQ100_MINIMUM = 90;
    private static final int SP400_MINIMUM = 350;
    private static final int SP600_MINIMUM = 550;

    public static final String SP500 = "S&P 500";
    public static final String NASDAQ100 = "NASDAQ 100";
    public static final String SP400 = "S&P 400";
    public static final String SP600 = "S&P 600";

    // Each selectable index is tracked via its most liquid, widely-used ETF — real, tradable
    // proxies for the index itself, since Alpaca's market data endpoint serves equities/ETFs, not
    // raw index values. No entry for FULL_MARKET: there's no single ETF proxy for "the entire US
    // market" in the same sense, so it's deliberately absent rather than mapped to a guess.
    public static final Map<String, String> INDEX_TO_ETF = Map.of(
            SP500, "SPY",
            SP400, "MDY",
            SP600, "SPSM",
            NASDAQ100, "QQQ"
    );

    private volatile Map<String, Set<String>> constituentsByIndex = Map.of();

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        Set<String> sp500 = loadRequired("sp500.txt", SP500, SP500_MINIMUM);
        Set<String> nasdaq100 = loadRequired("nasdaq100.txt", NASDAQ100, NASDAQ100_MINIMUM);
        Set<String> sp400 = loadRequired("sp400.txt", SP400, SP400_MINIMUM);
        Set<String> sp600 = loadRequired("sp600.txt", SP600, SP600_MINIMUM);

        this.constituentsByIndex = Map.of(SP500, sp500, NASDAQ100, nasdaq100, SP400, sp400, SP600, sp600);

        log.info("Index constituents loaded: S&P 500: {} symbols. NASDAQ 100: {} symbols. "
                        + "S&P 400: {} symbols. S&P 600: {} symbols.",
                sp500.size(), nasdaq100.size(), sp400.size(), sp600.size());
    }

    public Set<String> getConstituents(String indexName) {
        return constituentsByIndex.getOrDefault(indexName, Set.of());
    }

    // Fails application startup on purpose — a bundled resource file is either present and
    // well-formed, or the build/packaging is broken, and either way that must be caught here,
    // not discovered later as an empty or partial trading universe.
    private Set<String> loadRequired(String fileName, String indexName, int minimumExpected) {
        Set<String> symbols = readTickerFile(fileName);

        if (symbols.size() < minimumExpected) {
            throw new IllegalStateException(
                    "index-constituents/" + fileName + " has only " + symbols.size() + " tickers, "
                            + "expected at least " + minimumExpected + " for " + indexName
                            + " — refusing to start with a corrupted constituent list");
        }

        return symbols;
    }

    private Set<String> readTickerFile(String fileName) {
        ClassPathResource resource = new ClassPathResource(RESOURCE_DIR + fileName);
        Set<String> symbols = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String symbol = line.trim();
                if (!symbol.isEmpty()) {
                    symbols.add(symbol);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled resource " + RESOURCE_DIR + fileName, e);
        }

        return symbols;
    }
}
