# Schema Diagram

```mermaid
erDiagram
    users ||--o{ daily_trade : "places"
    users ||--o{ index_switch_history : "switches index"
    users ||--o{ daily_engine_log : "has one row per trading day"

    users {
        bigint id PK
        string email
        string alpaca_api_key_encrypted
        string alpaca_api_secret_encrypted
        string selected_index
        decimal investment_amount
        timestamp created_at
    }
    daily_recommendation {
        bigint id PK
        string filter_name
        string symbol
        string name
        decimal momentum_score
        decimal ret_6m
        decimal ret_3m
        decimal ret_1m
        decimal vol_3m
        timestamp scored_at
    }
    daily_trade {
        bigint id PK
        bigint user_id FK
        string symbol
        string action
        string status
        decimal amount
        decimal price_per_share
        decimal quantity
        string alpaca_order_id
        string index_filter
        timestamp traded_at
    }
    index_switch_history {
        bigint id PK
        bigint user_id FK
        string previous_index
        string new_index
        decimal investment_amount
        timestamp switched_at
    }
    daily_engine_log {
        bigint id PK
        bigint user_id FK
        date log_date
        string job1_status
        string job2_status
        string top5_symbols
        string rebalance_summary
        decimal portfolio_value
        timestamp created_at
    }
    scheduler_state {
        bigint id PK
        date job1_last_run_date
        date job1_last_success_date
        date job2_last_run_date
        bigint last_run_duration_ms
        int last_run_stocks_scored
        date job2_missed_alert_date
        date job1_failed_alert_date
    }
    backtest_result {
        bigint id PK
        string index_name
        date result_date
        decimal portfolio_value
        decimal benchmark_value
        string top5_symbols
        timestamp created_at
    }
```

`daily_trade`, `index_switch_history`, and `daily_engine_log` all link to `users` — every trade, index switch, and daily log entry belongs to someone. `daily_recommendation` is global, not per user: it's today's top 5 for each index (`filter_name`), the same for every user tracking that index, wiped and rewritten each morning Job 1 succeeds.

`daily_engine_log` gets one row per user per trading day (unique on `user_id` + `log_date`) regardless of outcome — a day with zero trades still gets a row saying why (market closed, holdings already matched, or a real failure), instead of a gap that looks identical to "the system never ran."

`scheduler_state` is a single row (`id = 1`) that survives restarts. `job1_last_run_date`/`job1_last_success_date` and `job2_last_run_date` are what let three independent trigger paths (in-process poller, external cron, manual admin call) all safely check "did today's job already happen" without racing each other. `job2_missed_alert_date` makes sure the "trading window missed" email only ever sends once per day; `job1_failed_alert_date` is the same guard for the "scoring failed today" email.

`backtest_result` is also global, like `daily_recommendation` — no `users` link, unique on (`index_name`, `result_date`). Written only by `scripts/backtest.py`, a separate daily process outside this Spring Boot app — never by the backend itself, which only ever reads it.
