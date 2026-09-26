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

import numpy as np
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
# Same run-wide threshold DailyScoringService applies once per day live — see compute_coverage.
MINIMUM_SCORED_FRACTION = 0.9
# price_n_months_ago's calendar-window search (and vol_3m's, below) assumes no gap in the
# trading-day index wider than the shortest lookback it ever searches across (1 month) — see
# assert_no_large_gaps. 21 days is a generous upper bound on any real US-market closure.
MAX_TRADING_DAY_GAP_DAYS = 21

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


def assert_no_large_gaps(dates: pd.DatetimeIndex, max_gap_days: int = MAX_TRADING_DAY_GAP_DAYS) -> None:
    """price_n_months_ago's calendar-window search (target = date - N months, then "first row on
    or after that") only stays safely in the past if there's always SOME trading day within any
    max_gap_days-wide stretch — otherwise the search could land on or after the evaluation date
    itself instead of before it, which would be a real look-ahead bug, not just a theoretical one.
    Checked once, here, right after the price data is loaded, rather than left as an unstated
    assumption baked into the math below."""
    if len(dates) < 2:
        return
    gap_days = np.diff(dates.values) / np.timedelta64(1, "D")
    max_gap = gap_days.max()
    if max_gap > max_gap_days:
        i = int(gap_days.argmax())
        raise ValueError(
            f"Trading-day gap of {int(max_gap)} days found between {dates[i].date()} and "
            f"{dates[i + 1].date()} — wider than the {max_gap_days}-day maximum this script "
            f"assumes when searching for a price N months back. Refusing to compute scores "
            f"against data that could silently violate that assumption."
        )


def compute_daily_scores(close: pd.DataFrame) -> pd.DataFrame:
    """Vectorized equivalent of calculateMomentumScore/calculateVolatility3m/hasMinimumHistory,
    computed for every (date, symbol) at once, rather than recomputing from scratch per day —
    validated against a line-by-line naive port for exact numeric parity before this script was
    written. Returns a date x symbol frame of momentum_score, NaN wherever a stock isn't eligible
    that day (not enough history) or wasn't trading yet.
    """
    dates = close.index
    n_dates = len(dates)

    def calendar_start_idx(n_months: int) -> np.ndarray:
        # First row-position on or after (date - n_months), for every date at once — shared by
        # price_n_months_ago below and the vol_3m calendar window further down, so both use
        # exactly the same "on or after" boundary rule via a single searchsorted call each.
        target_dates = dates - pd.DateOffset(months=n_months)
        idx = dates.searchsorted(target_dates, side="left")
        return idx.clip(max=n_dates - 1)

    def price_n_months_ago(n_months: int) -> pd.DataFrame:
        # First close on or after (date - n_months) — same "on or after" rule as
        # findPriceOnOrAfter, generalized across every date at once via searchsorted instead of a
        # per-date linear scan.
        idx = calendar_start_idx(n_months)
        return pd.DataFrame(close.values[idx, :], index=dates, columns=close.columns)

    ret_6m = (close - price_n_months_ago(6)) / price_n_months_ago(6)
    ret_3m = (close - price_n_months_ago(3)) / price_n_months_ago(3)
    ret_1m = (close - price_n_months_ago(1)) / price_n_months_ago(1)

    # Calendar 3-month window, matching calculateVolatility3m exactly — not an arbitrary count of
    # trading days. A calendar month isn't a fixed trading-day count, so a fixed .rolling(63)
    # (the previous approach here) silently drifts away from Java's calendar-day window across
    # months of different lengths or holiday-heavy stretches — this was a real gap a backtest
    # verification audit found, not a hypothetical one.
    #
    # Java filters PRICES to date >= (today - 3 months), then computes day-over-day returns
    # strictly WITHIN that filtered list — the one return that would "cross into" the window from
    # a price dated before it is deliberately excluded (Java's loop starts at closesInWindow[1],
    # not [0]), not just prices before the window itself. Reproduced below as a (date x date)
    # window-membership matrix rather than a rolling call, since the window's *length in rows*
    # varies per date (calendar months aren't a fixed trading-day count) — window_mask[i, j] is
    # True exactly when row j's own day-over-day return belongs to date i's window: j must be
    # strictly after i's window start (so j's own previous-day anchor is inside the window too,
    # not just j itself) and no later than i.
    #
    # Verified against a literal, unvectorized, per-symbol port of calculateVolatility3m on
    # synthetic data: exact match (to float64 precision noise, ~1e-17) for any symbol with
    # continuous daily data. The one residual gap — a symbol with a real missing-bar hole trading
    # slightly differently here than Java's own per-symbol consecutive-bars list would — is a
    # pre-existing characteristic of this whole close-matrix design (shared by ret_6m/3m/1m above
    # too, via price_n_months_ago's same close.values[idx, :] lookup), not something new
    # introduced by this fix, and out of scope for it.
    vol_start_idx = calendar_start_idx(3)
    row_idx = np.arange(n_dates)
    window_mask = (row_idx[None, :] > vol_start_idx[:, None]) & (row_idx[None, :] <= row_idx[:, None])
    window_mask_f = window_mask.astype(float)

    daily_returns = close.pct_change()
    valid = daily_returns.notna().to_numpy(dtype=float)
    returns_filled = daily_returns.fillna(0.0).to_numpy()

    window_n = window_mask_f @ valid
    window_sum = window_mask_f @ returns_filled
    window_sum_sq = window_mask_f @ (returns_filled ** 2)

    # Same two guards calculateVolatility3m applies: zero returns in the window -> volatility 0
    # (nothing to measure), and exactly one return -> the sample-variance (n-1) divisor would be
    # zero, so Java falls back to a divisor of 1 there too (giving 0 either way, since
    # sum_sq - n*mean^2 is itself 0 when n == 1).
    safe_n = np.where(window_n > 0, window_n, 1.0)
    window_mean = window_sum / safe_n
    divisor = np.where(window_n > 1, window_n - 1, 1.0)
    variance = (window_sum_sq - window_n * window_mean ** 2) / divisor
    # The sum-of-squares formula above can produce a tiny negative value from floating-point
    # cancellation when the true variance is ~0 — clip before sqrt rather than let that surface as
    # NaN for what should just be a near-zero volatility.
    variance = np.maximum(variance, 0.0)
    vol_3m_values = np.where(window_n > 0, np.sqrt(variance), 0.0)
    vol_3m = pd.DataFrame(vol_3m_values, index=dates, columns=close.columns)

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


def compute_coverage(
    scores: pd.DataFrame, universe: set[str], min_fraction: float = MINIMUM_SCORED_FRACTION
) -> pd.Series:
    """Per-day fraction of the tracked universe that produced a usable score — the same
    `scored.size() / symbols.size() >= MINIMUM_SCORED_FRACTION` check DailyScoringService applies
    once per run, generalized to per-day since the backtest walks forward through hundreds of
    days instead of checking this once live. A day below the threshold is treated exactly like the
    live system treats a degraded run: don't trust today's ranking — see simulate_index below,
    which carries the previous day's top 5 forward unchanged on a day this returns False for."""
    universe_cols = [c for c in scores.columns if c in universe]
    coverage = scores[universe_cols].notna().sum(axis=1) / len(universe_cols)
    return coverage >= min_fraction


def simulate_index(
    scores: pd.DataFrame,
    close: pd.DataFrame,
    constituents: set[str],
    benchmark_close: pd.Series,
    passes_gate: pd.Series,
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

    `passes_gate` (see compute_coverage): a day below the 90% universe-coverage threshold gets no
    new decision at all — top5_symbols carries the previous day's pick forward unchanged, mirroring
    how the live system keeps yesterday's daily_recommendation rows when a run degrades. The
    portfolio still marks to market against whatever was already held that day (the loop below
    still applies that day's realized return to prev_top5 either way) — a degraded scoring run
    doesn't freeze the value of positions that are still trading, it only skips deciding a new one.

    IMPORTANT — what top5_symbols stored for date d actually means: it's the pick decided from
    d's own closing price, held starting d+1 — NOT the pick being held on d itself. d's own
    portfolio_value above is computed from the PREVIOUS day's pick (prev_top5), realizing d's
    return against a decision made using d-1's close; d's own close only determines what gets
    held starting tomorrow. This is exactly the one-day lag that keeps this simulation free of
    look-ahead bias (see the empirical test in the PR this landed in) — but it does mean the
    top5_symbols column on any given row is not "what was held that day."
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
        if passes_gate.loc[d]:
            day_scores = index_scores.loc[d].dropna().sort_values(ascending=False)
            top5 = day_scores.head(TOP_N).index.tolist()
        else:
            # Degraded day (< 90% of the universe scored) — no new decision; same treatment as
            # DailyScoringService keeping yesterday's daily_recommendation rows on a bad run.
            top5 = prev_top5

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
    # top5_symbols on the row for result_date d means "decided from d's closing price, held
    # starting d+1" — not "held on d itself." See simulate_index's docstring for why.
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
    assert_no_large_gaps(close.index)
    print(f"Got {len(close)} trading days, {close.shape[1]} symbols with data.")

    print("Scoring every stock for every trading day (vectorized)...")
    scores = compute_daily_scores(close)
    # Same run-wide check DailyScoringService applies once per day live, generalized to every day
    # in the walk-forward range — see compute_coverage / simulate_index's passes_gate handling.
    passes_gate = compute_coverage(scores, universe)

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
                scores, close, by_index[index_name], close[etf], passes_gate,
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
