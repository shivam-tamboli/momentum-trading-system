# Sequence Diagrams

The three jobs that run automatically every trading day. Job 1 and Job 2 are triggered by an in-process poller checking Alpaca's own market clock every 60 seconds (not a fixed cron time); Job 3 runs on a plain fixed-time cron, since it only needs to happen well after close.

## 1. Job 1 — Daily Scoring

Retries every 15 minutes within its 3-hour window until it succeeds — the same "keep trying, not one shot" design as Job 2. If the window closes without ever succeeding, every eligible user gets a "Scoring Failed Today" email, mirroring Job 2's "Trading Window Missed" alert.

```mermaid
sequenceDiagram
    participant Scheduler
    participant Service as DailyScoringService
    participant Alpaca as Alpaca (System Key)
    participant DB
    participant Email

    Scheduler->>Service: market open within 3h, Job 1 not yet succeeded today
    Service->>Alpaca: fetch 6mo daily bars, batched 200 symbols/request, in parallel
    Alpaca-->>Service: closing prices

    loop each stock with enough history
        Service->>Service: ret_6m, ret_3m, ret_1m, vol_3m, momentum_score
    end

    Service->>Service: rank per index, keep top 5

    alt scored >= 90% of the universe
        Service->>DB: delete yesterday's daily_recommendation, insert today's (one transaction)
        Service->>DB: recordJob1Result COMPLETED, per user
        Service->>Email: today's top 5, per user
    else run scored too little of the universe
        Service->>DB: recordJob1Result FAILED, per user
        Note over DB: yesterday's daily_recommendation rows are left in place
    end
```

## 2. Job 2 — Rebalance (per eligible user, run in parallel)

```mermaid
sequenceDiagram
    participant Scheduler
    participant Service as DailyTradingService
    participant DB
    participant Alpaca as Alpaca (User Key)
    participant Email

    Scheduler->>Service: market open, Job 1 succeeded today, Job 2 not yet done
    Service->>DB: recordJob2Result IN_PROGRESS

    Service->>Alpaca: GET positions
    Alpaca-->>Service: current holdings
    Service->>DB: read today's top 5 for this user's index
    Service->>Service: diff — held-but-not-top5 = sell, top5-but-not-held = buy

    Service->>Alpaca: sell orders (fully completes before any buy starts)
    Alpaca-->>Service: fill confirmation (or still-pending after ~24s wait)
    Service->>DB: save daily_trade rows (FILLED or PENDING)

    alt buying power sufficient
        Service->>Alpaca: buy orders, sized off investment amount / full target count, minus safety buffer
        Alpaca-->>Service: fill confirmation (or still-pending)
        Service->>DB: save daily_trade rows (FILLED or PENDING)
    else insufficient buying power / amount too small / couldn't fetch buying power
        Service->>Service: record why buying was skipped
    end

    Service->>DB: recordJob2Result COMPLETED or NO_REBALANCE_NEEDED (skip reason included if buying was skipped)
    Service->>Email: portfolio rebalanced, or no-rebalance-needed
```

## 3. Job 3 — Reconciliation

```mermaid
sequenceDiagram
    participant Scheduler
    participant Service as TradeReconciliationService
    participant DB
    participant Alpaca as Alpaca (User Key)
    participant Email

    Scheduler->>Service: fixed cron, 21:00 UTC, weekdays
    Service->>DB: find every daily_trade still PENDING
    DB-->>Service: pending trades, any user

    loop each pending trade
        Service->>Alpaca: GET order by alpaca_order_id
        alt filled (or partially filled with a real fill)
            Service->>DB: update trade to FILLED, real price/qty/amount
        else canceled, expired, rejected, or suspended
            Service->>DB: update trade to FAILED
            Service->>Email: trade failed — action required
        else still genuinely open
            Note over Service: leave it PENDING, try again tomorrow
        end
    end
```
