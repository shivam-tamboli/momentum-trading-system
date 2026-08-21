# Technical Specification — Momentum Trading System

This document is the source of truth for building this system.
Read this fully before writing any code.

---

## 1. What This System Does

This is a weekly stock recommendation system. A momentum algorithm runs every week,
scores stocks from four US market indexes, and recommends which stocks to BUY, SELL,
or HOLD. Users connect their Alpaca paper trading account. The system places trades
on their behalf using their Alpaca API key.

**Users do not pick stocks. The algorithm decides.**

---

## 2. Two API Keys — Critical to Understand

There are two completely different Alpaca API keys in this system:

**System Key** (stored in environment variables)
- Belongs to our application, not any user
- Used ONLY for fetching historical stock price data from Alpaca
- Never used for placing trades
- Never stored in the database

**User Key** (stored in the database, encrypted)
- Belongs to the individual user
- Used ONLY for placing trades, checking account balance, and getting positions on behalf of that user
- Stored encrypted in the USER table using AES-256 encryption
- Decrypted in the service layer when needed, never exposed outside the service

---

## 3. What Alpaca Manages (Do NOT duplicate in our code)

Alpaca automatically handles the following for each user account. Do not build these yourself:

- Cash balance and buying power
- User's stock positions (what they own, quantity, average price)
- Balance deduction after a buy order
- Balance addition after a sell order
- Fractional share calculation for dollar-based orders

To get a user's balance: call `GET /account` using their key → Alpaca returns cash, buying_power, portfolio_value.
To get a user's positions: call `GET /positions` using their key → Alpaca returns list of stocks they hold.

---

## 4. Database — 5 Tables Only

**Do not create any table not listed here.**
**Do not create a Wallet table. Do not create a Position table.**
**Alpaca manages wallet and positions.**

### Table 1: USER
```sql
id                        BIGINT PRIMARY KEY AUTO_INCREMENT
email                     VARCHAR NOT NULL UNIQUE
alpaca_api_key_encrypted  VARCHAR NOT NULL   -- AES-256 encrypted
alpaca_api_secret_encrypted VARCHAR NOT NULL -- AES-256 encrypted
created_at                TIMESTAMP DEFAULT NOW()
```

### Table 2: STOCK
```sql
id          BIGINT PRIMARY KEY AUTO_INCREMENT
symbol      VARCHAR NOT NULL   -- e.g. AAPL, MSFT, TSLA
name        VARCHAR NOT NULL   -- e.g. Apple Inc.
index_name  VARCHAR NOT NULL   -- one of: S&P 500, S&P 400, S&P 600, Nasdaq 100
```

### Table 3: STOCK_PRICE
```sql
id          BIGINT PRIMARY KEY AUTO_INCREMENT
stock_id    BIGINT NOT NULL REFERENCES STOCK(id)
close_price DECIMAL(10,2) NOT NULL
price_date  DATE NOT NULL
fetched_at  TIMESTAMP DEFAULT NOW()
```

### Table 4: RECOMMENDATION
```sql
id              BIGINT PRIMARY KEY AUTO_INCREMENT
stock_id        BIGINT NOT NULL REFERENCES STOCK(id)
momentum_score  DECIMAL(10,6) NOT NULL
action          VARCHAR NOT NULL   -- one of: BUY, SELL, HOLD
index_name      VARCHAR NOT NULL   -- one of: S&P 500, S&P 400, S&P 600, Nasdaq 100
week_date       DATE NOT NULL      -- Monday of the week this recommendation applies to
created_at      TIMESTAMP DEFAULT NOW()
```

### Table 5: TRADE
```sql
id                BIGINT PRIMARY KEY AUTO_INCREMENT
user_id           BIGINT NOT NULL REFERENCES USER(id)
stock_id          BIGINT NOT NULL REFERENCES STOCK(id)
recommendation_id BIGINT NOT NULL REFERENCES RECOMMENDATION(id)
action            VARCHAR NOT NULL      -- BUY or SELL
amount            DECIMAL(10,2) NOT NULL -- dollar amount
price_per_share   DECIMAL(10,2) NOT NULL
quantity          DECIMAL(10,6) NOT NULL -- fractional shares supported
alpaca_order_id   VARCHAR NOT NULL       -- order ID returned by Alpaca
traded_at         TIMESTAMP DEFAULT NOW()
```

---

## 5. Algorithm — Momentum Formula

### Formula

momentum_score = (0.5 × ret_6m) + (0.3 × ret_3m) + (0.2 × ret_1m) - (0.1 × vol_3m)


### Variables
- `ret_6m` — percentage return over the last 6 months: (current_price - price_6m_ago) / price_6m_ago
- `ret_3m` — percentage return over the last 3 months: (current_price - price_3m_ago) / price_3m_ago
- `ret_1m` — percentage return over the last 1 month: (current_price - price_1m_ago) / price_1m_ago
- `vol_3m` — standard deviation of daily returns over the last 3 months (measures how much the price jumps around — higher is riskier, penalizes the score)

### Weights
The weights (0.5, 0.3, 0.2, 0.1) are constants in the service code.
**Do NOT store weights in the database.**

### Ranking
After calculating momentum_score for every stock in an index:
- Top 10 scores → action = BUY
- Bottom 10 scores → action = SELL
- Everything in between → action = HOLD

Save all results to the RECOMMENDATION table with the current week_date.

### When It Runs
Spring Scheduler triggers the algorithm every Monday at 9:00 AM EST
(after US markets open).
Cron expression: `0 0 9 * * MON`

---

## 6. API Endpoints

Authentication is handled by Supabase Auth.
Every request must include a valid Supabase JWT token in the Authorization header.
The controller verifies the userId in the path exists in the USER table before calling the service.

| Method | Path | Request Body | Response |
|--------|------|-------------|---------|
| GET | /:userId/account | none | { cash, buying_power, portfolio_value } |
| GET | /:userId/positions | none | [ { symbol, qty, avg_entry_price, current_price, unrealized_pl } ] |
| GET | /recommendations/snp500 | none | [ { symbol, name, momentum_score, action, week_date } ] |
| GET | /recommendations/snp400 | none | [ { symbol, name, momentum_score, action, week_date } ] |
| GET | /recommendations/snp600 | none | [ { symbol, name, momentum_score, action, week_date } ] |
| GET | /recommendations/nasdaq100 | none | [ { symbol, name, momentum_score, action, week_date } ] |
| POST | /:userId/trade/buy | { amount: number } | { trades: [ { symbol, amount_invested, shares_bought, price } ] } |
| POST | /:userId/trade/sell | none | { trades: [ { symbol, shares_sold, amount_received } ] } |
| GET | /:userId/trades | none | [ { symbol, action, amount, price_per_share, quantity, traded_at } ] |

**There is no /auth/register or /auth/login endpoint. Supabase handles this.**
**There is no /wallet/add-funds endpoint. Alpaca paper accounts start with $100,000.**

---

## 7. Buy Flow (step by step)

1. User sends POST /:userId/trade/buy { amount: 500 }
2. Controller verifies userId exists in USER table
3. Service reads this week's BUY recommendations from RECOMMENDATION table
4. Service calls Alpaca GET /account using user's decrypted key → gets buying_power
5. If amount > buying_power → return error "Insufficient balance in your Alpaca account"
6. If amount <= buying_power → divide amount equally across all BUY recommended stocks
7. For each stock: call Alpaca POST /orders { symbol, notional: amount_per_stock, side: buy, type: market }
8. Alpaca returns { order_id, filled_price, filled_qty }
9. Save each trade to TRADE table
10. Return { trades: [ { symbol, amount_invested, shares_bought, price } ] } to user

---

## 8. Sell Flow (step by step)

1. User sends POST /:userId/trade/sell
2. Controller verifies userId exists in USER table
3. Service reads this week's SELL recommendations from RECOMMENDATION table
4. Service calls Alpaca GET /positions using user's decrypted key → gets current positions
5. Find intersection: stocks user holds that are also on the SELL list
6. If no intersection → return { message: "No positions match this week's sell recommendations" }
7. For each matched stock: call Alpaca POST /orders { symbol, qty, side: sell, type: market }
8. Alpaca returns { order_id, filled_price, filled_qty }
9. Save each trade to TRADE table
10. Return { trades: [ { symbol, shares_sold, amount_received } ] } to user

---

## 9. Weekly Recommendation Flow (step by step)

This runs automatically. No user triggers this.

1. Spring Scheduler triggers every Monday at 9:00 AM EST
2. Service reads all stocks from STOCK table (all 4 indexes)
3. For each stock: call Alpaca GET /bars using system key → fetch 6 months of daily closing prices
4. Save prices to STOCK_PRICE table
5. For each stock: calculate ret_6m, ret_3m, ret_1m from STOCK_PRICE data
6. For each stock: calculate vol_3m (standard deviation of daily returns over last 3 months)
7. Apply formula: momentum_score = (0.5 × ret_6m) + (0.3 × ret_3m) + (0.2 × ret_1m) - (0.1 × vol_3m)
8. Group stocks by index_name
9. Per index: rank by momentum_score, label top 10 BUY, bottom 10 SELL, rest HOLD
10. Save all recommendations to RECOMMENDATION table with week_date = current Monday
11. Send email to all users: "New weekly recommendations are ready"

---

## 10. Security Rules

- Passwords: handled entirely by Supabase Auth — we never store passwords
- User Alpaca API key: encrypted with AES-256 before storing in USER table
- User Alpaca API secret: encrypted with AES-256 before storing in USER table
- Decryption key: stored in environment variable ENCRYPTION_KEY — never in code or database
- All API endpoints: protected by Supabase JWT token validation
- System Alpaca key: stored in environment variables only — never in database

---

## 11. Environment Variables
Alpaca System Account (for fetching market data only)

ALPACA_SYSTEM_API_KEY=
ALPACA_SYSTEM_API_SECRET=

Database

DB_URL=
DB_USERNAME=
DB_PASSWORD=

Encryption (for user Alpaca keys stored in DB)

ENCRYPTION_KEY=

Email (Spring Mail)

MAIL_HOST=
MAIL_PORT=
MAIL_USERNAME=
MAIL_PASSWORD=

Supabase Auth

SUPABASE_URL=
SUPABASE_ANON_KEY=


---

## 12. Maven Dependencies (Add to pom.xml)

```xml
<!-- Alpaca Java SDK -->
<dependency>
    <groupId>net.jacobpeterson</groupId>
    <artifactId>alpaca-java</artifactId>
    <version>10.1</version>
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

<!-- Spring Boot Starter Mail -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>

<!-- PostgreSQL Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>

<!-- Supabase JWT Validation -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>
```

---

## 13. Key Rules — Read Before Writing Any Code

1. Never create a Wallet table. Alpaca tracks user balance.
2. Never create a Position table. Alpaca tracks user positions.
3. Never store algorithm weights in the database. They are constants in the service code.
4. Never use the system Alpaca key for placing trades.
5. Never use the user Alpaca key for fetching market data.
6. Always decrypt the user key inside the service layer only — never pass the raw key outside the service.
7. Always check Alpaca account buying_power before placing a buy order.
8. Always check Alpaca positions before placing a sell order.
9. Recommendations are system-wide — not per user. One set of recommendations per week per index applies to all users.
10. The TRADE table is our audit log. Every order placed through Alpaca must be saved here.
