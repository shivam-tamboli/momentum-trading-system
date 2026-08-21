# Momentum Trading System

A weekly stock recommendation platform powered by a momentum algorithm. The system scores stocks from four US indexes every week and recommends which stocks to BUY, SELL, or HOLD. Users connect their own Alpaca paper trading account and the system places trades on their behalf.

## What It Does

- Runs a momentum algorithm every week automatically (no manual trigger needed)
- Scores and ranks all stocks from S&P 500, S&P 400, S&P 600, and Nasdaq 100
- Recommends top 10 stocks to BUY and bottom 10 to SELL per index
- Verifies user's Alpaca account balance before placing any trade
- Divides the user's chosen investment amount equally across recommended BUY stocks
- Sells stocks the user holds that appear on the SELL recommendation list
- Sends email notifications when new weekly recommendations are ready

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Database | PostgreSQL |
| Authentication | Supabase Auth |
| Scheduling | Spring Scheduler |
| Email | Spring Mail |
| Broker API | Alpaca Markets |

## Project Structure

momentum-trading-system/
src/
main/java/com/momentum/
controller/ → API endpoints
service/ → Business logic and algorithm
repository/ → Database access
model/ → Database table models
config/ → App configuration
docs/
schema-diagram.md
sequence-diagram.md
architecture-diagram.md
api-endpoints.md
TECHNICAL_SPEC.md
README.md


## Documentation

All system diagrams and specifications are in the `docs/` folder.

## Progress

### ✅ Completed
- **Project Setup** — Spring Boot 3.3.4, Java 21, all dependencies configured (`pom.xml`, `application.properties`)
- **JPA Entity Models** — 5 entity classes mapping to database tables:
  - `User` — stores user account and encrypted Alpaca API credentials
  - `Stock` — stores tracked stocks from S&P 500, S&P 400, S&P 600, Nasdaq 100
  - `StockPrice` — stores daily closing prices fetched from Alpaca
  - `Recommendation` — stores weekly BUY/SELL/HOLD recommendations per stock per index
  - `Trade` — stores every executed trade as an audit log
- **Enum** — `ActionType` (BUY, SELL, HOLD) for type-safe action fields
- **Repositories** — 5 Spring Data JPA repositories (zero SQL written):
  - `UserRepository`, `StockRepository`, `StockPriceRepository`, `RecommendationRepository`, `TradeRepository`

### 🔄 In Progress
- Nothing currently in progress

### 📋 Up Next
- AES-256 encryption utility (for storing user Alpaca keys)
- Supabase JWT authentication filter
- Alpaca API client configuration
- Momentum algorithm service
- Weekly scheduler
- Trade service (buy and sell flows)
- Controllers (9 API endpoints)
- Email notification service
- Stock seeder (populate STOCK table)

## Environment Variables Required

ALPACA_SYSTEM_API_KEY=
ALPACA_SYSTEM_API_SECRET=
DB_URL=
DB_USERNAME=
DB_PASSWORD=
ENCRYPTION_KEY=
MAIL_HOST=
MAIL_PORT=
MAIL_USERNAME=
MAIL_PASSWORD=
SUPABASE_URL=
SUPABASE_ANON_KEY=
