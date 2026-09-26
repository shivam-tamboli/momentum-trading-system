#!/usr/bin/env python3
"""
Walk-forward momentum backtest, run daily by .github/workflows/backtest.yml. The only thing that
ever writes to backtest_result (see BacktestResult.java) — the Spring Boot app only ever reads it
(BacktestController), never computes or writes it.

Same formula as DailyScoringService (Java), applied to every historical trading day instead of
just "today": for each day, score every stock using its trailing 6 months of split-adjusted daily
closes (0.5*ret_6m + 0.3*ret_3m + 0.2*ret_1m - 0.1*vol_3m), rank per index, and simulate an
equal-weighted portfolio that rebalances to that day's top 5 every day. Compared against the
relevant index ETF compounding its own actual daily returns over the same range.

Stored as a normalized cumulative-return index, both starting at 100 on the first backfilled day —
never a dollar amount for any specific investment. The frontend scales this by whatever amount the
user enters; this script and the backend never compute or store a dollar figure.

First run per index: backfills 2 years. Every run after: only inserts days strictly after that
index's own last stored result_date — existing rows are never recomputed or overwritten. Uses
plain floats, not BigDecimal-exact precision — this is an illustrative simulation, not a live
trading calculation, and the scale of this computation (~1,500 stocks x ~500 trading days x 5
indexes) is what makes a vectorized-with-pandas approach necessary instead of a line-by-line port
of the Java loop.
"""

import os
import re
import sys
from pathlib import Path

import pandas as pd
import psycopg2
import requests

RESOURCE_DIR = (
    Path(__file__).resolve().parent.parent / "backend" / "src" / "main" / "resources" / "index-constituents"
)

FILTER_FILES = {
    "S&P 500": "sp500.txt",
    "S&P 400": "sp400.txt",
    "S&P 600": "sp600.txt",
    "NASDAQ 100": "nasdaq100.txt",
}
FULL_MARKET = "FULL_MARKET"
ALL_INDEXES = [*FILTER_FILES.keys(), FULL_MARKET]

# Deliberately its own map, separate from IndexConstituentService.INDEX_TO_ETF (which has no
# FULL_MARKET entry). This feature covers all 5 tracked options; S&P 600 uses SPSM, same ETF as
# the live benchmark-comparison feature elsewhere in the app.
BENCHMARK_ETF = {
    "S&P 500": "SPY",
    "S&P 400": "MDY",
    "S&P 600": "SPSM",
    "NASDAQ 100": "QQQ",
    FULL_MARKET: "VTI",
}

BACKFILL_YEARS = 2
LOOKBACK_MONTHS = 6  # trailing window the formula itself needs, same as DailyScoringService
TOP_N = 5
BATCH_SIZE = 200
BASE_INDEX_VALUE = 100.0

ALPACA_DATA_URL = "https://data.alpaca.markets/v2/stocks/bars"


def alpaca_headers() -> dict:
    return {
        "APCA-API-KEY-ID": os.environ["ALPACA_SYSTEM_API_KEY"],
        "APCA-API-SECRET-KEY": os.environ["ALPACA_SYSTEM_API_SECRET"],
    }


def db_connect():
    db_url = os.environ["DB_URL"]
    m = re.match(r"jdbc:postgresql://([^:/]+):(\d+)/([^?]+)", db_url)
    if not m:
        raise ValueError(f"Could not parse DB_URL: {db_url}")
    host, port, dbname = m.group(1), m.group(2), m.group(3)
    return psycopg2.connect(
        host=host,
        port=port,
        dbname=dbname,
        user=os.environ["DB_USERNAME"],
        password=os.environ["DB_PASSWORD"],
        sslmode="require",
    )


def load_constituents() -> tuple[dict[str, set[str]], set[str]]:
    """Same 4 text files the live backend reads off the classpath — never a separate data
    source. FULL_MARKET is the union of all 4, matching DailyScoringService's own universe."""
    by_index: dict[str, set[str]] = {}
    union: set[str] = set()
    for index_name, filename in FILTER_FILES.items():
        symbols = {line.strip() for line in (RESOURCE_DIR / filename).read_text().splitlines() if line.strip()}
        by_index[index_name] = symbols
        union |= symbols
    by_index[FULL_MARKET] = union
    return by_index, union


def fetch_bars(symbols: list[str], start: pd.Timestamp, end: pd.Timestamp) -> dict[str, list[dict]]:
    """Batched exactly like DailyScoringService.fetchBarsForBatch: SPLIT adjustment, IEX feed,
    up to BATCH_SIZE symbols per request, paginated via page_token."""
    symbols = sorted(symbols)
    all_bars: dict[str, list[dict]] = {}
    for i in range(0, len(symbols), BATCH_SIZE):
        batch = symbols[i : i + BATCH_SIZE]
        page_token = None
        while True:
            params = {
                "symbols": ",".join(batch),
                "timeframe": "1Day",
                "start": start.date().isoformat(),
                "end": end.date().isoformat(),
                "limit": 10000,
                "adjustment": "split",
                "feed": "iex",
            }
            if page_token:
                params["page_token"] = page_token
            resp = requests.get(ALPACA_DATA_URL, headers=alpaca_headers(), params=params, timeout=30)
            resp.raise_for_status()
            data = resp.json()
            for symbol, bars in (data.get("bars") or {}).items():
                all_bars.setdefault(symbol, []).extend(bars)
            page_token = data.get("next_page_token")
            if not page_token:
                break
    return all_bars


def bars_to_close_frame(all_bars: dict[str, list[dict]]) -> pd.DataFrame:
    """Pivoted date x symbol close-price matrix, sorted by date — everything downstream is
    vectorized off this instead of looping per symbol per day."""
    records = [
        {"symbol": symbol, "date": bar["t"][:10], "close": bar["c"]}
        for symbol, bars in all_bars.items()
        for bar in bars
    ]
    if not records:
        return pd.DataFrame()
    df = pd.DataFrame.from_records(records)
    df["date"] = pd.to_datetime(df["date"])
    return df.pivot_table(index="date", columns="symbol", values="close").sort_index()


def compute_daily_scores(close: pd.DataFrame) -> pd.DataFrame:
    """Vectorized equivalent of calculateMomentumScore/calculateVolatility3m/hasMinimumHistory,
    computed for every (date, symbol) at once, rather than recomputing from scratch per day —
    validated against a line-by-line naive port for exact numeric parity before this script was
    written. Returns a date x symbol frame of momentum_score, NaN wherever a stock isn't eligible
    that day (not enough history) or wasn't trading yet.
    """
    dates = close.index

    def price_n_months_ago(n_months: int) -> pd.DataFrame:
        # First close on or after (date - n_months) — same "on or after" rule as
        # findPriceOnOrAfter, generalized across every date at once via searchsorted instead of a
        # per-date linear scan.
        target_dates = dates - pd.DateOffset(months=n_months)
        idx = dates.searchsorted(target_dates, side="left")
        idx = idx.clip(max=len(dates) - 1)
        return pd.DataFrame(close.values[idx, :], index=dates, columns=close.columns)

    ret_6m = (close - price_n_months_ago(6)) / price_n_months_ago(6)
    ret_3m = (close - price_n_months_ago(3)) / price_n_months_ago(3)
    ret_1m = (close - price_n_months_ago(1)) / price_n_months_ago(1)

    # Rolling ~3-month (63 trading day) sample stdev, ddof=1 — same n-1 divisor as
    # calculateVolatility3m.
    daily_returns = close.pct_change()
    vol_3m = daily_returns.rolling(window=63, min_periods=2).std(ddof=1)

    score = 0.5 * ret_6m + 0.3 * ret_3m + 0.2 * ret_1m - 0.1 * vol_3m

    # Eligibility: a stock needs at least 3 months of history before this date — same rule as
    # hasMinimumHistory. first_valid is a fixed date per symbol; broadcast against every date at
    # once rather than looping.
    first_valid = close.apply(lambda col: col.first_valid_index())
    min_required_start = dates - pd.DateOffset(months=3)
    eligible = pd.DataFrame(
        first_valid.values[None, :] <= min_required_start.values[:, None],
        index=dates,
        columns=close.columns,
    )

    return score.where(eligible)


def simulate_index(
    scores: pd.DataFrame,
    close: pd.DataFrame,
    constituents: set[str],
    benchmark_close: pd.Series,
    start_after: pd.Timestamp,
    seed_portfolio_value: float,
    seed_benchmark_value: float,
    seed_top5: list[str],
) -> pd.DataFrame:
    """For each trading day strictly after `start_after`: rank this index's constituents by
    momentum score, take the top 5, and compound the portfolio by their equal-weighted day-over-
    day return — same delta as the live system's "rebalance to today's top 5 every day," just
    simplified to an equal-weighted basket return instead of literally replaying buy/sell diffs,
    since only the resulting value curve matters here, not individual trade records.

    `seed_*` carries the simulation forward correctly on an incremental (non-first) run: the day
    right after the last stored day needs both the portfolio/benchmark value AND the previous
    day's top 5 (to know what return to apply) from that last stored row, not a fresh reset to the
    base index value.
    """
    index_scores = scores[[c for c in scores.columns if c in constituents]]
    dates = index_scores.index[index_scores.index > start_after]

    daily_returns = close.pct_change()
    benchmark_returns = benchmark_close.pct_change()

    rows = []
    portfolio_value = seed_portfolio_value
    benchmark_value = seed_benchmark_value
    prev_top5 = seed_top5

    for d in dates:
        day_scores = index_scores.loc[d].dropna().sort_values(ascending=False)
        top5 = day_scores.head(TOP_N).index.tolist()

        # No prior day's top 5 at all yet (the very first day of the very first backfill) — stays
        # flat at the seed value, same as the benchmark on that same day.
        if prev_top5:
            day_returns = daily_returns.loc[d, prev_top5].dropna()
            if len(day_returns) > 0:
                portfolio_value *= 1 + day_returns.mean()
            bret = benchmark_returns.loc[d]
            if pd.notna(bret):
                benchmark_value *= 1 + bret

        rows.append(
            {
                "date": d,
                "portfolio_value": portfolio_value,
                "benchmark_value": benchmark_value,
                "top5_symbols": ",".join(top5),
            }
        )
        prev_top5 = top5 if top5 else prev_top5

    return pd.DataFrame(rows)


def last_stored_rows(conn) -> dict[str, dict]:
    """The most recent row per index, if any — the seed a subsequent run needs to continue the
    simulation from instead of resetting to the base index value."""
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT DISTINCT ON (index_name) index_name, result_date, portfolio_value, benchmark_value, top5_symbols
            FROM backtest_result
            ORDER BY index_name, result_date DESC
            """
        )
        return {
            row[0]: {
                "date": pd.Timestamp(row[1]),
                "portfolio_value": float(row[2]),
                "benchmark_value": float(row[3]),
                "top5_symbols": row[4].split(",") if row[4] else [],
            }
            for row in cur.fetchall()
        }


def insert_results(conn, index_name: str, rows: pd.DataFrame) -> None:
    if rows.empty:
        return
    with conn.cursor() as cur:
        for _, row in rows.iterrows():
            cur.execute(
                """
                INSERT INTO backtest_result
                    (index_name, result_date, portfolio_value, benchmark_value, top5_symbols, created_at)
                VALUES (%s, %s, %s, %s, %s, NOW())
                ON CONFLICT (index_name, result_date) DO NOTHING
                """,
                (index_name, row["date"].date(), row["portfolio_value"], row["benchmark_value"], row["top5_symbols"]),
            )
    conn.commit()


def main() -> None:
    by_index, universe = load_constituents()
    all_symbols = universe | set(BENCHMARK_ETF.values())
    today = pd.Timestamp.now(tz="UTC").normalize().tz_localize(None)

    # Check what's already stored BEFORE fetching any price data, so an ordinary incremental run
    # only fetches the trailing lookback window the formula needs plus whatever's new — never the
    # full backfill range again. Only a genuine first run (no index has any stored row yet) fetches
    # the full BACKFILL_YEARS of history.
    conn = db_connect()
    last_rows = last_stored_rows(conn)

    first_run_start = today - pd.DateOffset(years=BACKFILL_YEARS)
    per_index_start_after = {index_name: last_rows[index_name]["date"] if index_name in last_rows
                              else first_run_start for index_name in ALL_INDEXES}
    earliest_start_after = min(per_index_start_after.values())
    fetch_start = earliest_start_after - pd.DateOffset(months=LOOKBACK_MONTHS)

    print(f"Fetching {len(all_symbols)} symbols from {fetch_start.date()} to {today.date()} "
          f"(earliest index needs data from {earliest_start_after.date()})...")
    all_bars = fetch_bars(sorted(all_symbols), fetch_start, today)
    close = bars_to_close_frame(all_bars)
    if close.empty:
        print("No bars returned at all — aborting without writing anything.", file=sys.stderr)
        conn.close()
        sys.exit(1)
    print(f"Got {len(close)} trading days, {close.shape[1]} symbols with data.")

    print("Scoring every stock for every trading day (vectorized)...")
    scores = compute_daily_scores(close)

    try:
        for index_name in ALL_INDEXES:
            etf = BENCHMARK_ETF[index_name]
            if etf not in close.columns:
                print(f"  {index_name}: benchmark {etf} has no price data — skipping", file=sys.stderr)
                continue

            seed = last_rows.get(index_name)
            if seed is not None:
                start_after = seed["date"]
                seed_portfolio_value = seed["portfolio_value"]
                seed_benchmark_value = seed["benchmark_value"]
                seed_top5 = seed["top5_symbols"]
            else:
                # First run for this index: nothing to seed from, so the walk-forward range
                # starts BACKFILL_YEARS ago and the simulation starts flat at the base value — the
                # day immediately after (today - BACKFILL_YEARS) is the first day actually stored.
                start_after = first_run_start
                seed_portfolio_value = BASE_INDEX_VALUE
                seed_benchmark_value = BASE_INDEX_VALUE
                seed_top5 = []

            new_rows = simulate_index(
                scores, close, by_index[index_name], close[etf],
                start_after, seed_portfolio_value, seed_benchmark_value, seed_top5,
            )

            print(f"  {index_name}: {len(new_rows)} new day(s) to insert (last stored: "
                  f"{seed['date'].date() if seed else 'none — first run'})")
            insert_results(conn, index_name, new_rows)
    finally:
        conn.close()

    print("Done.")


if __name__ == "__main__":
    main()
