# Momentum Trading System

I built a system that trades stocks on its own, every day, based on price momentum. It scores around 1,500 stocks across five tracked options — S&P 500, S&P 400, S&P 600, Nasdaq 100, and the full combined universe (Full Market) — every morning before the market opens, picks the top 5 per option, and rebalances every connected user's account to match — no button to click, no manual approval step. It trades with fake money — Alpaca's paper trading API — so the mechanics are real but nothing here risks real cash.

**Live demo:** https://momentum-trading-system-eight.vercel.app
**Deep dive:** [ARCHITECTURE.md](ARCHITECTURE.md) — diagrams, algorithm details, database schema, API reference, technical decisions, and the real bugs I hit building this.

## How it actually runs

Three jobs, every trading day:

1. **Scoring (Job 1)** — pulls 6 months of daily bars for every tracked stock and scores each one: `0.5×return_6m + 0.3×return_3m + 0.2×return_1m − 0.1×volatility_3m`. Needs at least 3 months of history to qualify, and the whole run gets thrown out if it scores less than 90% of the universe — a bad run doesn't get to quietly publish a broken top 5.
2. **Trading (Job 2)** — for each user, diffs their current Alpaca holdings against today's top 5 for their chosen index, sells what dropped out, buys what's newly in. Only touches the delta, not a full liquidate-and-rebuy.
3. **Reconciliation (Job 3)** — runs automatically at 21:00 UTC, well after close. An order can outlive the code watching it — Job 2 only waits ~24 seconds per order before moving on — so this checks Alpaca's own record for anything still marked PENDING and moves it to FILLED or FAILED.

Job 1 and Job 2 aren't triggered by a fixed cron time. `DailyEngineSchedulerService` polls Alpaca's own market clock every 60 seconds and retries Job 1 every 15 minutes in the 3 hours before open until it succeeds, then retries Job 2 every 15 minutes for as long as the market's open, until it's confirmed done. This is deliberate — a hardcoded "9:30am" cron is exactly the kind of thing that quietly stops working the day Alpaca's clock and your assumption disagree, and a single failed attempt at either job shouldn't be able to silently skip the entire day. Job 3 doesn't need any of that — it just has to run sometime after close, so a plain fixed-time cron is enough.

The catch: the clock-polling part of this runs in-process, and the backend is on Render's free tier, which sleeps after ~15 minutes idle. A sleeping process can't poll anything. So there's a GitHub Actions workflow (`daily-trading-cron.yml`) that triggers all three jobs directly at fixed UTC times as an external backup, plus a keep-alive workflow and an external uptime pinger to reduce how often the backend is actually asleep when it matters. GitHub Actions' own cron has been measured running 2–6 hours late on this repo, so it's a backup for the backup, not the primary mechanism — the in-process poller is what actually keeps Job 1 and Job 2 reliable.

If the market opens and Job 1 never managed to succeed, or the market closes and Job 2 never managed to run, every eligible user gets a "scoring failed" or "trading window missed" email — either way, a silent no-op is never the outcome.

## Security-relevant stuff worth knowing

- **Alpaca API keys are encrypted at rest** — AES-256-GCM, keyed by `ENCRYPTION_KEY`. Values are stored with an `enc:v1:` prefix so a future format change can migrate safely without a big-bang rewrite.
- **Auth is Supabase JWT, verified once per request** — `JwtAuthFilter` checks the token against Supabase and puts the resolved email on `SecurityContextHolder`; every controller reads that instead of re-verifying the token itself.
- **CORS is scoped to this project's own Vercel deployments**, not a single hardcoded URL — `https://momentum-trading-system-*.vercel.app`, so PR preview deployments actually work against the real backend instead of failing CORS silently.

## Email

Sent via Resend's HTTPS API, not SMTP — Render's free tier blocks outbound SMTP (port 587) entirely, which used to just hang forever with no error until the request eventually timed out. HTTPS on 443 doesn't have that problem. Every send is wrapped so a failed email can never take down the trading or scoring run it's reporting on; failures are tracked and visible on `/metrics` instead of buried in logs.

Emails sent: today's top 5, portfolio rebalanced, index switch confirmed, no rebalancing needed, scoring failed, trading window missed, trade failed (this one's flagged as needing attention, not just informational), and an on-demand test email for checking deliverability.

## Tech stack

- **Backend** — Java 21, Spring Boot 3.3.4
- **Database** — PostgreSQL via Supabase, Hibernate/JPA
- **Market data + execution** — Alpaca (alpaca-java SDK)
- **Auth** — Supabase Auth
- **Email** — Resend (HTTPS API)
- **Frontend** — Next.js 16, React 19, Tailwind CSS 4, shadcn/ui
- **Charts** — lightweight-charts (TradingView)
- **Data fetching** — TanStack React Query
- **Index data pipeline** — Python script + GitHub Actions
- **Hosting** — Render (backend), Vercel (frontend)

## Quick start

Need Java 21, Node.js, and a Postgres database.

```bash
# Backend — create backend/.env first, see variables below
cd backend
mvn spring-boot:run
# runs on http://localhost:8080

# Frontend — create frontend/.env.local first, see variables below
cd frontend
npm install
npm run dev
# runs on http://localhost:3000
```

## Environment variables

**`backend/.env`**

```
ALPACA_SYSTEM_API_KEY=
ALPACA_SYSTEM_API_SECRET=
DB_URL=
DB_USERNAME=
DB_PASSWORD=
RESEND_API_KEY=
RESEND_FROM_ADDRESS=
ENCRYPTION_KEY=
SUPABASE_URL=
SUPABASE_ANON_KEY=
ADMIN_SECRET_KEY=
```

`ENCRYPTION_KEY` is a base64-encoded 256-bit key — generate one with `openssl rand -base64 32`. There's no default; the app fails to start without it, on purpose. `RESEND_FROM_ADDRESS` can be left as Resend's sandbox sender (`onboarding@resend.dev`) until you verify your own domain.

**`frontend/.env.local`**

```
NEXT_PUBLIC_SUPABASE_URL=
NEXT_PUBLIC_SUPABASE_ANON_KEY=
NEXT_PUBLIC_API_BASE_URL=
```
