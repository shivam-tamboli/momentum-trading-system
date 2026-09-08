# Momentum Trading System

I built a system that trades stocks on its own, every day, based on price momentum. It scores around 1,500 stocks across four US indexes every morning before the market opens, picks the top 5 per index, and rebalances every connected user's account to match — no button to click, no manual approval step. It trades with fake money — Alpaca's paper trading API — so the mechanics are real but nothing here risks real cash.

**Live demo:** https://momentum-trading-system-eight.vercel.app
**Deep dive:** [ARCHITECTURE.md](ARCHITECTURE.md) — diagrams, algorithm details, database schema, API reference, technical decisions, and the real bugs I hit building this.

## Tech stack

- **Backend** — Java 21, Spring Boot 3.3.4
- **Database** — PostgreSQL via Supabase, Hibernate/JPA
- **Market data + execution** — Alpaca (alpaca-java SDK)
- **Auth** — Supabase Auth
- **Frontend** — Next.js 16, React 19, Tailwind CSS 4, shadcn/ui
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
MAIL_HOST=
MAIL_PORT=
MAIL_USERNAME=
MAIL_PASSWORD=
SUPABASE_URL=
SUPABASE_ANON_KEY=
ADMIN_SECRET_KEY=
```

**`frontend/.env.local`**

```
NEXT_PUBLIC_SUPABASE_URL=
NEXT_PUBLIC_SUPABASE_ANON_KEY=
NEXT_PUBLIC_API_BASE_URL=
```
