# Technical Specification — Momentum Trading System

This is the current technical contract for this system — what's actually built and running, not a plan for something yet to be built. If you're changing this system, read this first; if the code and this doc ever disagree, the code is right and this doc is stale.

---

## 1. What This System Does

This is a daily automatic rebalancing system. A momentum algorithm runs every trading morning before the market opens, scores stocks across four US indexes plus the full combined universe, and picks the top 5 per index. Users connect their Alpaca paper trading account, pick one of five tracked options (S&P 500, S&P 400, S&P 600, Nasdaq 100, or the full market), and set an investment amount. Every trading day, the system diffs each user's actual Alpaca holdings against that day's top 5 for their chosen index and rebalances automatically — sells what dropped out, buys what's newly in.

**Users do not pick stocks, and users do not click a button to trade. The algorithm decides, and the schedule executes it.** The only trade a user directly causes is switching their tracked index, which immediately sells everything and buys the new index's top 5 (market permitting) rather than waiting for the next scheduled run.

A separate daily process (not part of the Spring Boot app — see section 9) walks the same formula backward across 2 years of history per index, so the algorithm's actual past performance can be shown against a benchmark ETF, not just today's picks.

---

## 2. Two API Keys — Critical to Understand

There are two completely different Alpaca API keys in this system:

**System key** (`ALPACA_SYSTEM_API_KEY` / `ALPACA_SYSTEM_API_SECRET`, environment variables only)
- Belongs to the application, not any user
- Used only for fetching historical price data during scoring
- Never used for placing trades
- Never stored in the database

**User key** (stored in the `users` table, encrypted)
- Belongs to the individual user
- Used only for placing trades, checking account balance, and reading positions on that user's behalf
- Stored as AES-256-GCM ciphertext, prefixed `enc:v1:` so a future encryption format change can migrate safely without a hard cutover
- Decrypted in the service layer only, right before an Alpaca call — never logged, never returned in any API response

---

## 3. What Alpaca Manages (do not duplicate in our code)

Alpaca handles all of the following for each connected account. None of it is reimplemented here:

- Cash balance and buying power
- Positions (what's held, quantity, average entry price)
- Balance deduction after a buy, balance addition after a sell
- Fractional-share calculation for dollar-based (notional) orders

To get a user's balance: call `GET /account` with their key → Alpaca returns cash, buying power, portfolio value, last equity.
To get a user's positions: call `GET /positions` with their key → Alpaca returns everything they currently hold.

---

## 4. Database — 7 tables

**Do not add a wallet or positions table.** Alpaca is the source of truth for both; this system never mirrors either into its own database.

### `users`
```sql
id                          BIGINT PRIMARY KEY
email                       VARCHAR NOT NULL UNIQUE
alpaca_api_key_encrypted    VARCHAR              -- AES-256-GCM, enc:v1: prefix
alpaca_api_secret_encrypted VARCHAR              -- AES-256-GCM, enc:v1: prefix
selected_index              VARCHAR              -- one of the 5 values in section 1, or null if unset
investment_amount           DECIMAL              -- per-rebalance-cycle amount, or null until set
created_at                  TIMESTAMP
```
A row is created automatically the first time a logged-in user hits `/me` — there's no separate register step. `alpaca_api_key_encrypted`/`selected_index`/`investment_amount` all start null; the system won't trade for a user until all three are set.

### `daily_recommendation`
```sql
id              BIGINT PRIMARY KEY
filter_name     VARCHAR NOT NULL   -- which index this row belongs to
symbol          VARCHAR NOT NULL
name            VARCHAR NOT NULL
momentum_score  DECIMAL NOT NULL
ret_6m          DECIMAL
ret_3m          DECIMAL
ret_1m          DECIMAL
vol_3m          DECIMAL
scored_at       TIMESTAMP
```
Global, not per user — one set of top-5 rows per index per day, wiped and rewritten every morning Job 1 runs. `ret_*`/`vol_3m` are the formula's own inputs, kept alongside the score so the UI can show the breakdown, not just the final number.

### `daily_trade`
```sql
id                BIGINT PRIMARY KEY
user_id           BIGINT NOT NULL REFERENCES users(id)
symbol            VARCHAR NOT NULL
action            VARCHAR NOT NULL   -- BUY or SELL (no HOLD — a stock the system isn't touching just doesn't get a row)
status            VARCHAR NOT NULL   -- FILLED, PENDING, or FAILED
amount            DECIMAL            -- dollar amount, null until filled
price_per_share   DECIMAL            -- null until filled
quantity          DECIMAL            -- fractional shares supported
alpaca_order_id   VARCHAR
index_filter      VARCHAR            -- which index this trade was made under
traded_at         TIMESTAMP
```
This is the audit log — every order placed through Alpaca, on this user's behalf, gets a row here regardless of how it turned out.

### `index_switch_history`
```sql
id                BIGINT PRIMARY KEY
user_id           BIGINT NOT NULL REFERENCES users(id)
previous_index    VARCHAR            -- null on a user's very first pick
new_index         VARCHAR NOT NULL
investment_amount DECIMAL            -- the amount actually in effect at the moment of the switch
switched_at       TIMESTAMP
```

### `daily_engine_log`
```sql
id                 BIGINT PRIMARY KEY
user_id            BIGINT NOT NULL REFERENCES users(id)
log_date           DATE NOT NULL     -- unique per (user_id, log_date)
job1_status        VARCHAR NOT NULL  -- COMPLETED, FAILED, or NOT_RUN
job2_status        VARCHAR NOT NULL  -- IN_PROGRESS, COMPLETED, NO_REBALANCE_NEEDED, MARKET_CLOSED, or FAILED
top5_symbols       TEXT              -- comma-separated, for that day/index
rebalance_summary  TEXT              -- plain-English one-liner, e.g. "Bought SNDK, MU. Sold INTC, PANW."
portfolio_value    DECIMAL
created_at         TIMESTAMP
```
One row per user per trading day, written regardless of outcome — a day with zero trades still gets a row explaining why (market closed, holdings already matched, or a real failure). This is what lets Trade History show something honest for a day with no `daily_trade` rows, instead of a gap indistinguishable from "the system never ran."

### `scheduler_state`
```sql
id                       BIGINT PRIMARY KEY   -- single row, id = 1
job1_last_run_date       DATE
job1_last_success_date   DATE
job2_last_run_date       DATE                 -- the real guard against re-running Job 2 twice in one day
last_run_duration_ms     BIGINT
last_run_stocks_scored   INT
job2_missed_alert_date   DATE                 -- so the "trading window missed" email only ever sends once per day
job1_failed_alert_date  DATE                 -- so the "scoring failed today" email only ever sends once per day
```
Survives restarts on purpose — this state used to live only in memory, and a restart between Job 1 succeeding and Job 2 firing used to silently skip an entire trading day for every user.

### `backtest_result`
```sql
id              BIGINT PRIMARY KEY
index_name      VARCHAR NOT NULL   -- same 5 filter-name values as daily_recommendation
result_date     DATE NOT NULL      -- unique per (index_name, result_date)
portfolio_value DECIMAL NOT NULL   -- normalized index, starts at 100 — never a dollar amount
benchmark_value DECIMAL NOT NULL   -- same normalization, that index's benchmark ETF
top5_symbols    TEXT               -- comma-separated, that day's simulated top 5
created_at      TIMESTAMP
```
Global, not per user, like `daily_recommendation`. Written only by `scripts/backtest.py` — a separate Python process, run daily by `.github/workflows/backtest.yml`, not the Spring Boot app. The backend only ever reads this table (`GET /backtest/{index}`); it never computes or writes a row here itself. See section 9 below.

---

## 5. Algorithm — Momentum Formula

```
momentum_score = (0.5 × ret_6m) + (0.3 × ret_3m) + (0.2 × ret_1m) − (0.1 × vol_3m)
```

- `ret_6m` / `ret_3m` / `ret_1m` — percentage price change over each window: `(current_price − price_N_ago) / price_N_ago`
- `vol_3m` — standard deviation of daily returns over the last 3 months. Higher volatility docks the score, even if the trend is up.

The weights (0.5, 0.3, 0.2, 0.1) are constants in `DailyScoringService`. **Do not store them in the database** — nothing about this system expects them to be user-adjustable or tunable per run.

Before a stock is scored at all, it has to clear two checks: at least 3 months of price history (otherwise a newly listed stock's short window would get miscounted as a full 6-month return), and the run as a whole has to successfully score at least 90% of the ~1,500-stock universe, or the entire run is thrown out and the prior day's recommendations are left in place. There is no BUY/SELL/HOLD label stored anywhere — a stock is either in an index's top 5 for the day or it isn't. Ranking happens per index (`filter_name`), independently — a stock's rank in the S&P 500 has nothing to do with its rank in the full-market universe.

**When it runs:** not on a fixed cron time. `DailyEngineSchedulerService` polls Alpaca's own market clock every 60 seconds and retries Job 1 every 15 minutes within the 3-hour window before market open, until it succeeds — a single transient failure doesn't skip scoring for the entire day. A hardcoded time (the original design used `0 0 9 * * MON`, once a week) breaks the moment a holiday shifts market open — this doesn't have that failure mode.

---

## 6. API Endpoints

See [`docs/api-endpoints.md`](api-endpoints.md) for the full table — kept in one place to avoid the two files drifting apart. Every request needs a Supabase JWT except `/admin/**` (`X-Admin-Key`) and `/health` (nothing). Every `:userId` route verifies the caller's own token actually resolves to that user before doing anything else.

---

## 7. Job 2 — Automatic Rebalance Flow (step by step)

Nothing here is user-triggered on a normal day — this runs automatically, once per user, once per trading day.

1. Scheduler confirms Job 1 succeeded today; if it didn't, Job 2 sits out entirely rather than rebalancing against stale data.
2. For each user with a saved Alpaca key, an investment amount, and a chosen index: fetch their live positions from Alpaca (never from our own database — Alpaca is the only source of truth here).
3. Fetch today's top 5 for that user's chosen index from `daily_recommendation`.
4. Diff: symbols currently held but not in today's top 5 → sell. Symbols in today's top 5 but not currently held → buy. Everything else is left untouched.
5. Sell first, fully, before any buy starts. Each sell order waits up to ~24 seconds for Alpaca to confirm a fill; if it doesn't confirm in time, the trade is recorded PENDING (not a fabricated fill) and gets picked up by Job 3 later.
6. Buys are sized off the user's investment amount, split evenly across the *full* target allocation count — not just however many symbols happen to be new that day — minus a safety buffer, so a partial rotation doesn't get sized as if it were the only holding.
7. Every attempted order — filled, pending, or failed — gets a row in `daily_trade`.
8. A `daily_engine_log` row is written for the day regardless of outcome: real trades, "holdings already match, nothing to do," market was closed, or a failure with the reason.
9. An email goes out to the user reporting what happened — including an explicit note if a buy was skipped (e.g. insufficient buying power) even though sells went through, rather than just silently reporting fewer trades than expected.

Switching your tracked index (`POST /users/me/selected-index`) runs a variant of this immediately: sell 100% of current holdings first, fully, then buy the new index's top 5 — instead of only touching the delta.

---

## 8. Job 1 — Daily Scoring Flow (step by step)

This runs automatically, once per trading day, before market open. No user triggers it.

1. In-process poller attempts Job 1 once market open is within 3 hours, based on Alpaca's real clock, not a hardcoded time — and retries every 15 minutes within that window until it succeeds, gated on success rather than merely "did we already attempt today."
2. Fetch 6 months of daily closing prices for every tracked symbol, using the **system** key, batched (200 symbols per request) and run in parallel — not one request per symbol.
3. Per symbol: compute `ret_6m`, `ret_3m`, `ret_1m` from those prices, and `vol_3m` (standard deviation of daily returns over the last 3 months).
4. Apply the formula in section 5.
5. Group by index (`filter_name`), rank by `momentum_score`, keep the top 5 per index.
6. If fewer than 90% of the universe scored successfully, throw out the entire run and keep yesterday's `daily_recommendation` rows in place — a partial or broken run never gets to publish a broken top 5.
7. Otherwise: delete yesterday's rows and insert today's, in a single transaction (a crash between the delete and the insert must never leave the table empty).
8. Send each user a "today's top 5" email for their tracked index.
9. If the window closes (market opens) without Job 1 ever having succeeded that day, every eligible user gets a "Scoring Failed Today" email instead — once, not on every poll.

---

## 9. Backtest — Separate Process (do not confuse with Job 1)

`scripts/backtest.py`, run daily at 22:00 UTC (weekdays) by `.github/workflows/backtest.yml` — not part of the Spring Boot app, not triggered by `DailyEngineSchedulerService`, and not something the app ever calls.

- Applies the exact same formula as section 5 to every historical trading day over the last 2 years (not just today), per index, and simulates an equal-weighted portfolio that rebalances to that day's top 5 — compared against that index's benchmark ETF compounding its own real daily returns over the same range.
- Reads the same 4 constituent text files as the live backend (`backend/src/main/resources/index-constituents/`) — never a separate data source.
- Uses the **system** Alpaca key only, for historical bars — same rule as Job 1, never a user key, never for placing trades. This script places no trades at all.
- Connects to the same Postgres database directly (`psycopg2`, not JPA) and writes only to `backtest_result`. Needs its own copies of `ALPACA_SYSTEM_API_KEY`, `ALPACA_SYSTEM_API_SECRET`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` as GitHub Actions repository secrets — separate from the Spring Boot app's Render environment variables, even though the values are the same.
- First run per index backfills 2 years from a base value of 100 (both series). Every run after continues from that index's own last stored row — never refetches or recomputes the full range again.
- `GET /backtest/{index}` (see section 6) is read-only — the backend serves whatever's stored and never computes or writes a backtest result itself.

---

## 10. Security Rules

- Passwords: handled entirely by Supabase Auth — never stored here.
- Alpaca key/secret: AES-256-GCM encrypted before storing, keyed by `ENCRYPTION_KEY` — the app refuses to start if this isn't set, on purpose, rather than silently falling back to storing plaintext.
- Decryption happens in the service layer only, right before the Alpaca call that needs it — a decrypted key is never logged, never returned in a response, never passed further than it has to be.
- System Alpaca key: environment variables only, never the database.
- Every endpoint needs a valid Supabase JWT, except `/admin/**` (a separate `X-Admin-Key`, reserved for the routes that actually place orders or trigger scoring) and `/health` (nothing, so an uptime monitor gets a real `200` instead of a `401`).
- The JWT itself is verified once per request, by one filter (`JwtAuthFilter`), which resolves it to an email and stores that as the request's principal — every controller reads that instead of re-checking the token itself.
- Every `:userId` route checks that the resolved principal actually owns that `userId` before doing anything. This wasn't always true — an earlier version trusted the number in the URL with no ownership check at all, letting any logged-in user read or affect any other user's account by changing one digit in the path.

---

## 11. Environment Variables

Alpaca system account (market data only, never trading):
```
ALPACA_SYSTEM_API_KEY=
ALPACA_SYSTEM_API_SECRET=
```

Database:
```
DB_URL=
DB_USERNAME=
DB_PASSWORD=
```

Encryption (for user Alpaca keys stored in the database):
```
ENCRYPTION_KEY=
```
Base64-encoded 256-bit key. No default — the app fails to start without it.

Email (Resend's HTTPS API — not SMTP; Render's free tier blocks outbound SMTP on port 587 entirely, which used to just hang forever with no error):
```
RESEND_API_KEY=
RESEND_FROM_ADDRESS=
```

Supabase Auth:
```
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

Admin routes (`/admin/**` — manual scoring/trading triggers, reconciliation, test email):
```
ADMIN_SECRET_KEY=
```

---

## 12. Maven Dependencies (current, `backend/pom.xml`)

```xml
<!-- Alpaca Java SDK -->
<dependency>
    <groupId>net.jacobpeterson</groupId>
    <artifactId>alpaca-java</artifactId>
    <version>9.2.0</version>
</dependency>

<!-- Spring Boot Starter Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Spring Boot Starter Data JPA -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- PostgreSQL Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- jjwt — present as a dependency, but NOT what actually verifies tokens today.
     JwtAuthFilter delegates verification to a live call against Supabase's own
     /auth/v1/user endpoint instead of parsing/validating the JWT locally. -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>

<!-- Lombok (pinned to 1.18.42 — required for JDK 21 annotation processing compatibility) -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.42</version>
    <optional>true</optional>
</dependency>

<!-- Loads backend/.env for local development -->
<dependency>
    <groupId>io.github.cdimascio</groupId>
    <artifactId>dotenv-java</artifactId>
    <version>3.0.0</version>
</dependency>
```

There is no `spring-boot-starter-mail` — it was removed when email moved from SMTP to Resend's HTTPS API (email is sent with the same `RestTemplate` used elsewhere, not a dedicated mail client).

---

## 13. Key Rules

1. Never create a wallet or positions table — Alpaca is the source of truth for both.
2. Never store the momentum formula's weights in the database — they're constants in `DailyScoringService`.
3. Never use the system Alpaca key for placing trades, and never use a user's key for fetching market data during scoring.
4. Always decrypt a user's key inside the service layer, immediately before the Alpaca call that needs it — never pass a decrypted key any further than that.
5. Always check buying power before a buy, always check live positions before a sell — both against Alpaca directly, never against a locally cached copy.
6. Recommendations are index-scoped, not per-user — one set of top-5 rows per index per day applies to every user tracking that index.
7. `daily_trade` is the audit log. Every order actually placed through Alpaca — filled, pending, or failed — gets a row.
8. `daily_engine_log` gets a row every trading day regardless of outcome, including days with zero trades — a blank gap and "the system ran and correctly found nothing to do" must never look the same.
9. Every `:userId` route must verify the caller's token actually resolves to that `userId` before touching anything. This is not optional and was, for a period, missing entirely.
10. A failed non-critical operation (an email send, a `daily_engine_log` write) must never be allowed to abort the trading run it's reporting on. Log it, track it, move on.
11. `backtest_result` is written only by `scripts/backtest.py`. Never write to it from the Spring Boot app, and never use a user's Alpaca key for it — same system-key-only rule as Job 1.
