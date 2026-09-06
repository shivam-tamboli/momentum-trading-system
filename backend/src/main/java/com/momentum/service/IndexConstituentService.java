package com.momentum.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index membership is a pure filter list, held in memory only — no DB table, no paid API (FMP's
 * free tier doesn't cover constituent endpoints, and S&P 400/600 aren't offered by FMP at all,
 * even on paid plans — confirmed by testing). Sourced from four free, public GitHub-hosted files,
 * verified individually (row counts checked against each index's known size). Loaded once at
 * startup, refreshed daily.
 *
 * Never a data source for scoring — only used to filter DailyScoringService's already-computed
 * scores at read time ("just filter the scores using the ticker list for that index").
 */
@Service
public class IndexConstituentService {

    private static final Logger log = LoggerFactory.getLogger(IndexConstituentService.class);

    // CSV with a header row (Wikipedia-sourced, community-maintained).
    private static final String SP500_CSV_URL =
            "https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv";
    private static final String NASDAQ100_CSV_URL =
            "https://raw.githubusercontent.com/Gary-Strauss/NASDAQ100_Constituents/master/data/nasdaq100_constituents.csv";

    // Plain one-ticker-per-line lists, no header — derived from real ETF holdings (MDY / SPSM),
    // auto-refreshed daily by that repo's own GitHub Action.
    private static final String SP400_TICKER_LIST_URL =
            "https://raw.githubusercontent.com/major/index-etfs/main/tickers/mdy.txt";
    private static final String SP600_TICKER_LIST_URL =
            "https://raw.githubusercontent.com/major/index-etfs/main/tickers/spsm.txt";

    // GitHub's commits API, filtered to the one file, gives that file's real last-modified date —
    // used only to detect a source that's gone stale/unmaintained, never to fetch data itself.
    private static final String SP500_COMMITS_API_URL =
            "https://api.github.com/repos/datasets/s-and-p-500-companies/commits"
                    + "?path=data/constituents.csv&sha=main&per_page=1";
    private static final String NASDAQ100_COMMITS_API_URL =
            "https://api.github.com/repos/Gary-Strauss/NASDAQ100_Constituents/commits"
                    + "?path=data/nasdaq100_constituents.csv&sha=master&per_page=1";
    private static final String SP400_COMMITS_API_URL =
            "https://api.github.com/repos/major/index-etfs/commits?path=tickers/mdy.txt&sha=main&per_page=1";
    private static final String SP600_COMMITS_API_URL =
            "https://api.github.com/repos/major/index-etfs/commits?path=tickers/spsm.txt&sha=main&per_page=1";

    private static final int STALE_THRESHOLD_DAYS = 90;
    private static final int CONSECUTIVE_FAILURE_ALERT_THRESHOLD = 3;

    public static final String SP500 = "S&P 500";
    public static final String NASDAQ100 = "NASDAQ 100";
    public static final String SP400 = "S&P 400";
    public static final String SP600 = "S&P 600";

    // Explicit timeouts matter here: plain RestTemplate() has none, so a slow/stalled connection
    // (observed in testing — the JVM's default HTTP client can hang far longer than a plain curl
    // to the same URL) would block startup indefinitely instead of falling through to the
    // per-index failure handling below.
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    // Replaced wholesale on each refresh, never mutated in place — volatile read is enough for
    // safe cross-thread visibility without needing a lock for the (much more frequent) reads.
    private volatile Map<String, Set<String>> constituentsByIndex =
            Map.of(SP500, Set.of(), NASDAQ100, Set.of(), SP400, Set.of(), SP600, Set.of());

    // Consecutive-failure-day tracking, per index — in-memory only (resets on restart, same as
    // constituentsByIndex itself). "3 days in a row" means 3 distinct calendar dates, not 3
    // attempts — refresh() can run more than once on the same day (startup, Job 1, a manual
    // /admin/sync-index-constituents call) without inflating the count.
    private final Map<String, Integer> consecutiveFailureDays = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> lastFailureRecordedDate = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        String summary = refresh();
        log.info("Index constituents loaded on startup: {}", summary);
    }

    public String refresh() {
        Map<String, Set<String>> updated = new HashMap<>();
        StringBuilder summary = new StringBuilder();

        updated.put(SP500, fetchSymbols(SP500_CSV_URL, SP500_COMMITS_API_URL, SP500, summary,
                csv -> parseCsvColumn(csv, "Symbol")));
        updated.put(NASDAQ100, fetchSymbols(NASDAQ100_CSV_URL, NASDAQ100_COMMITS_API_URL, NASDAQ100, summary,
                csv -> parseCsvColumn(csv, "Ticker")));
        updated.put(SP400, fetchSymbols(SP400_TICKER_LIST_URL, SP400_COMMITS_API_URL, SP400, summary,
                this::parsePlainTickerList));
        updated.put(SP600, fetchSymbols(SP600_TICKER_LIST_URL, SP600_COMMITS_API_URL, SP600, summary,
                this::parsePlainTickerList));

        this.constituentsByIndex = updated;
        return summary.toString().trim();
    }

    public Set<String> getConstituents(String indexName) {
        return constituentsByIndex.getOrDefault(indexName, Set.of());
    }

    private Set<String> fetchSymbols(String url, String commitsApiUrl, String indexName, StringBuilder summary,
                                      java.util.function.Function<String, Set<String>> parser) {
        try {
            String content = restTemplate.getForObject(url, String.class);
            Set<String> symbols = parser.apply(content);
            log.info("Loaded {} constituents for {}", symbols.size(), indexName);
            summary.append(indexName).append(": ").append(symbols.size()).append(" symbols. ");

            consecutiveFailureDays.remove(indexName);
            lastFailureRecordedDate.remove(indexName);
            checkStaleness(commitsApiUrl, indexName);

            return symbols;
        } catch (Exception e) {
            log.error("Failed to load constituents for {}: {}", indexName, e.getMessage(), e);
            summary.append(indexName).append(": FAILED (").append(e.getMessage()).append("). ");
            recordFailureAndMaybeAlert(indexName);
            // Keep whatever we already had rather than wiping a working list out on a transient failure.
            return constituentsByIndex.getOrDefault(indexName, Set.of());
        }
    }

    // Only ever bumps the counter once per calendar day, so repeated refresh() calls on the same
    // day (startup + Job 1 + a manual admin trigger) don't fabricate extra "failure days."
    private void recordFailureAndMaybeAlert(String indexName) {
        LocalDate today = LocalDate.now();
        LocalDate lastRecorded = lastFailureRecordedDate.get(indexName);

        if (today.equals(lastRecorded)) {
            return;
        }

        lastFailureRecordedDate.put(indexName, today);
        int days = consecutiveFailureDays.merge(indexName, 1, Integer::sum);

        if (days >= CONSECUTIVE_FAILURE_ALERT_THRESHOLD) {
            log.error("Index constituent source has been unavailable for {} consecutive days — "
                    + "manual intervention required.", days);
        }
    }

    private void checkStaleness(String commitsApiUrl, String indexName) {
        try {
            GitHubCommit[] commits = restTemplate.getForObject(commitsApiUrl, GitHubCommit[].class);
            if (commits == null || commits.length == 0 || commits[0].commit() == null
                    || commits[0].commit().committer() == null
                    || commits[0].commit().committer().date() == null) {
                log.warn("Could not determine last commit date for {} constituent source", indexName);
                return;
            }

            Instant lastCommitInstant = Instant.parse(commits[0].commit().committer().date());
            long daysSinceLastCommit = ChronoUnit.DAYS.between(lastCommitInstant, Instant.now());

            if (daysSinceLastCommit > STALE_THRESHOLD_DAYS) {
                LocalDate lastCommitDate = lastCommitInstant.atZone(ZoneOffset.UTC).toLocalDate();
                log.warn("Warning — {} constituent list may be stale. Last updated {}. Please verify "
                        + "the source is still maintained.", indexName, lastCommitDate);
            }
        } catch (Exception e) {
            log.warn("Failed to check staleness for {} constituent source: {}", indexName, e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCommit(GitHubCommitDetail commit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCommitDetail(GitHubCommitPerson committer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCommitPerson(String date) {
    }

    private Set<String> parsePlainTickerList(String content) {
        if (content == null || content.isBlank()) {
            return Set.of();
        }

        Set<String> symbols = new HashSet<>();
        for (String line : content.split("\\r?\\n")) {
            String symbol = line.trim();
            if (!symbol.isEmpty()) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private Set<String> parseCsvColumn(String csv, String columnName) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }

        String[] lines = csv.split("\\r?\\n");
        List<String> header = splitCsvLine(lines[0]);
        int columnIndex = header.indexOf(columnName);
        if (columnIndex < 0) {
            throw new IllegalStateException("Column '" + columnName + "' not found in CSV header: " + header);
        }

        Set<String> symbols = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> fields = splitCsvLine(lines[i]);
            if (columnIndex < fields.size()) {
                String symbol = fields.get(columnIndex).trim();
                if (!symbol.isEmpty()) {
                    symbols.add(symbol);
                }
            }
        }
        return symbols;
    }

    // Minimal quote-aware CSV line splitter — handles fields like "Saint Paul, Minnesota" that
    // contain commas inside quotes, without pulling in a full CSV library for two small files.
    private List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
