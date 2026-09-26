# API Endpoints

Every response field is `snake_case` (Jackson's global naming strategy — see `application.properties`), regardless of the camelCase names in the Java code below.

Every route needs a Supabase JWT in `Authorization: Bearer <token>`, except `/admin/**` (needs `X-Admin-Key` instead) and `/health` (needs nothing at all). Every route that takes a `:userId` checks that the caller's own token actually resolves to that user — a request for someone else's account gets a 403, not their data.

| Method | Path | Description | Request body | Response |
|---|---|---|---|---|
| GET | `/health` | Public liveness check, no auth | none | `{ status }` |
| GET | `/me` | Get the current user, auto-creating a bare row on first call | none | `{ id, email, has_alpaca_key, selected_index, investment_amount }` |
| PUT | `/users/me/alpaca-key` | Save or replace the user's Alpaca key/secret (encrypted before storing) | `{ alpacaApiKey, alpacaApiSecret }` | same shape as `/me` |
| POST | `/users/me/selected-index` | Change tracked index — sells everything held, buys the new index's top 5, if the market's open | `{ selectedIndex }` | same shape as `/me` |
| GET | `/users/me/index-switch-history` | Every past index switch for this user, most recent first | none | `[ { previous_index, new_index, investment_amount, switched_at } ]` |
| PUT | `/users/me/investment-amount` | Set how much to invest per rebalance cycle | `{ investmentAmount }` | same shape as `/me` |
| GET | `/:userId/account` | Live cash, buying power, portfolio value, straight from Alpaca | none | `{ cash, buying_power, portfolio_value, last_equity }` |
| GET | `/:userId/positions` | Live positions, straight from Alpaca | none | `[ { symbol, name, qty, avg_entry_price, current_price, market_value, unrealized_pl, unrealized_pl_percent } ]` |
| GET | `/:userId/benchmark` | This user's portfolio return vs. their tracked index's return, same period | none | `{ selected_index, index_symbol, period_start, portfolio_return_percent, index_return_percent }` |
| GET | `/:userId/daily-trades` | This user's trade history | none | `[ { symbol, action, status, amount, price_per_share, quantity, traded_at, index_filter } ]` |
| GET | `/:userId/engine-log` | Last 30 days of this user's daily engine log — what Job 1/Job 2 actually did, even on a day with zero trades | none | `[ { log_date, job1_status, job2_status, top5_symbols, rebalance_summary, portfolio_value } ]` |
| GET | `/engine-status` | Whether today is a trading day, and whether Job 1 / Job 2 have run yet | none | `{ today, is_trading_day, job1_last_success_date, job2_last_run_date }` |
| GET | `/recommendations/snp500` | Today's top 5, S&P 500 | none | `[ { symbol, name, momentum_score, scored_at, ret_6m, ret_3m, ret_1m, vol_3m } ]` |
| GET | `/recommendations/sp400` | Today's top 5, S&P 400 | none | same shape |
| GET | `/recommendations/sp600` | Today's top 5, S&P 600 | none | same shape |
| GET | `/recommendations/nasdaq100` | Today's top 5, Nasdaq 100 | none | same shape |
| GET | `/recommendations/full-market` | Today's top 5 across the whole ~1,500-stock scored universe | none | same shape |
| GET | `/backtest/snp500` | 2-year walk-forward simulation vs. benchmark ETF, S&P 500 | none | `{ index_name, etf_symbol, points: [ { date, portfolio_value, benchmark_value, top5_symbols } ] }` |
| GET | `/backtest/sp400` | Same, S&P 400 | none | same shape |
| GET | `/backtest/sp600` | Same, S&P 600 | none | same shape |
| GET | `/backtest/nasdaq100` | Same, Nasdaq 100 | none | same shape |
| GET | `/backtest/full-market` | Same, Full Market | none | same shape |
| GET | `/index-price-history?index=` | 30-day price history for an index's tracking ETF | none | `{ index, etf_symbol, points: [ { date, close } ] }` |
| GET | `/stock-price-history?symbols=` | 14-day price history for a comma-separated list of symbols (sparklines) | none | `{ "<symbol>": [ { date, close } ], ... }` |
| GET | `/metrics` | Health, last scoring run, trade counts, email delivery status | none | `{ health, algorithm, trading, database, email }` |
| POST | `/admin/run-daily-scoring` | Manually trigger Job 1 (X-Admin-Key) | none | plain-text result |
| POST | `/admin/run-daily-trading` | Manually trigger Job 2 (X-Admin-Key) | none | plain-text result |
| POST | `/admin/reconcile-pending-trades` | Manually trigger Job 3 — resolve any trade still stuck PENDING (X-Admin-Key) | none | plain-text result |
| POST | `/admin/test-email` | Send a real test email through Resend, to confirm delivery is actually working (X-Admin-Key) | none | `{ success, sent_at, error }` |

Notes:

- There's no `/auth/register` or `/auth/login` endpoint — Supabase handles that entirely, the backend only ever sees an already-issued JWT.
- There's no `/wallet/add-funds` endpoint — Alpaca paper accounts start with $100,000 automatically.
- There's no manual `/trade/buy` or `/trade/sell` endpoint — trading isn't something a user triggers. Job 2 runs automatically every trading day and rebalances every connected account to that day's top 5 for their chosen index. The only way to cause an immediate trade outside that daily run is switching your tracked index, which sells everything and buys the new index's top 5 right away (market permitting).
- `X-Admin-Key` is reserved for the handful of routes that actually place orders or run scoring — `/metrics` used to live under `/admin/` too, but moved to plain JWT auth since the frontend has nowhere secure to hold an admin key (anything shipped in client JS is readable via devtools).
- The `/backtest/*` routes are read-only, same as `/recommendations/*` — they just serve whatever `scripts/backtest.py` last wrote to `backtest_result`. The backend never computes or writes a backtest itself.
