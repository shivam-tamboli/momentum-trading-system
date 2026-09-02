# Momentum Trading System

This is a small trading app that picks stocks for you based on momentum, and lets you buy or sell them with a click. It uses fake money (paper trading through Alpaca), so nothing here risks real cash.

## What is this

Every week, the app looks at stocks from four US indexes (S&P 500, S&P 400, S&P 600, and Nasdaq 100), scores each one based on how well it's been performing lately, and sorts them into three buckets: BUY, SELL, or HOLD.

You don't pick stocks yourself. The algorithm does that part. Your job is just to connect your Alpaca paper trading account, look at what it recommends, and decide whether to buy or sell.

## Why I built this

I wanted to see if a simple, rules-based trading idea (momentum investing — buy what's been going up, sell what's been going down) could be turned into something you actually use, end to end: real market data in, a real algorithm doing the scoring, real (paper) orders going out to a broker.

It's a proof of concept, not a finished product. The goal was to get the whole loop working — data, scoring, trading, and a UI to see it all — not to build the next Robinhood.

## How the algorithm works (plain English)

For every stock, the app looks at:

- how much the price has gone up or down over the last 6 months
- how much it's changed over the last 3 months
- how much it's changed over the last 1 month
- how bumpy the price has been over the last 3 months (this is the "risk" part — a stock that jumps around a lot gets penalized)

It combines these into one score, weighting the 6-month trend the most and the recent bumpiness against the stock. Then, within each index, it sorts every stock by that score. The top ones become BUY, the bottom ones become SELL, and everything in the middle is HOLD.

That's it. No machine learning, no black box. Just: what's been trending up, minus a penalty for how risky it's been.

## Tech stack

Backend:
- Java 21 + Spring Boot
- PostgreSQL (hosted on Supabase)
- Alpaca API for market data and paper trading
- Supabase Auth for login

Frontend:
- Next.js + React
- Tailwind CSS + shadcn/ui components
- React Query for data fetching

Hosting:
- Backend on Render
- Frontend on Vercel

## Live demo

- App: https://momentum-trading-system-eight.vercel.app
- API: https://momentum-trading-backend.onrender.com

Heads up: buying and selling only work while the US stock market is open. Outside market hours the app will tell you when it reopens instead of pretending to place an order.

## Running it locally

You'll need Java 21, Node.js, and a Postgres database (Supabase works fine, or any Postgres instance).

**Backend**

```bash
cd backend
# create a .env file in this folder — see "Environment variables" below
mvn spring-boot:run
```

The backend starts on `http://localhost:8080`.

**Frontend**

```bash
cd frontend
npm install
# create a .env.local file in this folder — see "Environment variables" below
npm run dev
```

The frontend starts on `http://localhost:3000`.

## Environment variables

**Backend** (`backend/.env`)

```
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
```

The Alpaca system key is only used to fetch market data for the algorithm — it never places trades. Each user's own Alpaca key (which they enter when they sign up) is what actually places their orders, and it's encrypted before it's stored.

**Frontend** (`frontend/.env.local`)

```
NEXT_PUBLIC_SUPABASE_URL=
NEXT_PUBLIC_SUPABASE_ANON_KEY=
NEXT_PUBLIC_API_BASE_URL=
```

## How to use it

1. **Sign up / log in.** Auth is handled by Supabase — just an email and password.
2. **Connect your Alpaca account.** On first login you'll be asked for your Alpaca paper trading API key and secret. Alpaca gives these to you for free when you make a paper trading account — no real money involved.
3. **Run the algorithm.** There's a "Run Algorithm" button on the dashboard. It fetches the latest prices and generates this week's BUY/SELL/HOLD list for all four indexes. This normally happens automatically every Monday, but you can trigger it manually too.
4. **Check the recommendations.** Go to the Recommendations page to see what's rated BUY, SELL, or HOLD for each index, along with the momentum score behind it.
5. **Buy.** Enter a dollar amount and hit Buy. The app splits that amount evenly across every BUY-rated stock and places the orders for you.
6. **Sell.** Hit Sell and the app checks what you currently hold against this week's SELL list, and sells whatever matches. If you don't hold anything on the SELL list, it'll just tell you that — no error, no fake trade.
7. **Check your trade history.** Every order you've placed, with the price and quantity you actually got, shows up on the Trade History page.

## What's next

Some things I'd still like to improve:

- Show a live status while buying/selling instead of just a loading spinner, since placing a full batch of orders can take a little while
- Handle orders that don't fill right away more gracefully, instead of just marking them as $0
- Let people sell stocks they own even if those stocks aren't on this week's SELL list
- General cleanup — this was built fast, as a proof of concept, so there's room to make the code more solid

## Docs

More detailed technical notes (database schema, sequence diagrams, API reference) are in the `docs/` folder if you want to go deeper.
