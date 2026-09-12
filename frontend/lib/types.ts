export interface AccountResponse {
  cash: number;
  buying_power: number;
  portfolio_value: number;
  // Alpaca's own record of the previous trading day's closing equity — real, not estimated.
  last_equity: number;
}

export interface ErrorResponse {
  error: string;
}

export interface PositionItem {
  symbol: string;
  name: string;
  qty: number;
  avg_entry_price: number;
  current_price: number;
  market_value: number;
  unrealized_pl: number;
  unrealized_pl_percent: number;
}

export interface IndexPricePoint {
  date: string;
  close: number;
}

export interface IndexPriceHistoryResponse {
  index: string;
  etf_symbol: string;
  points: IndexPricePoint[];
}

// Backend returns Map<String, List<PricePoint>> — a plain object keyed by symbol, points reusing
// the same {date, close} shape as IndexPricePoint.
export type StockPriceHistoryResponse = Record<string, IndexPricePoint[]>;

export interface BenchmarkResponse {
  selected_index: string | null;
  // Null when there's no single-ETF proxy for the tracked index (no index chosen yet, or
  // FULL_MARKET) or when there isn't yet enough portfolio history to compute a return.
  index_symbol: string | null;
  period_start: string | null;
  portfolio_return_percent: number | null;
  index_return_percent: number | null;
}

export interface IndexSwitchHistoryItem {
  // Null for a user's very first index pick — there was no prior selection to record.
  previous_index: string | null;
  new_index: string;
  investment_amount: number | null;
  switched_at: string;
}

export interface MeResponse {
  id: number;
  email: string;
  has_alpaca_key: boolean;
  selected_index: string | null;
  investment_amount: number | null;
}

export interface DailyRecommendationItem {
  symbol: string;
  name: string;
  momentum_score: number;
  scored_at: string;
  // Nullable — rows written before this was tracked have none, though daily_recommendation is
  // wiped and rewritten every scoring run so that gap closes itself within a day.
  ret_6m: number | null;
  ret_3m: number | null;
  ret_1m: number | null;
  vol_3m: number | null;
}

export type TradeStatus = 'FILLED' | 'PENDING' | 'FAILED';

export interface DailyTradeItem {
  symbol: string;
  action: 'BUY' | 'SELL';
  status: TradeStatus;
  // Null whenever the real value isn't known yet — a PENDING buy's share count and price aren't
  // determinable until it fills, and a FAILED order never got any fill data at all. Never a
  // fabricated 0/$0 standing in for "unknown".
  amount: number | null;
  price_per_share: number | null;
  quantity: number | null;
  traded_at: string;
  // Null for trades placed before this field existed — not backfilled with a guess.
  index_filter: string | null;
}

export const SELECTABLE_INDEXES = ['S&P 500', 'S&P 400', 'S&P 600', 'NASDAQ 100', 'FULL_MARKET'] as const;
export type SelectableIndex = (typeof SELECTABLE_INDEXES)[number];

export const INDEX_RECOMMENDATION_PATH: Record<SelectableIndex, string> = {
  'S&P 500': 'snp500',
  'S&P 400': 'sp400',
  'S&P 600': 'sp600',
  'NASDAQ 100': 'nasdaq100',
  FULL_MARKET: 'full-market',
};

export interface HealthStatus {
  status: 'UP' | 'DOWN';
}

export interface AlgorithmStats {
  status: 'NEVER_RUN' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  last_run_at: string | null;
  duration_ms: number | null;
  stocks_scored: number | null;
  last_error: string | null;
}

export interface TradingStats {
  total_trades: number;
  buy_count: number;
  sell_count: number;
}

export interface DatabaseStats {
  stock_count: number;
  recommendation_count: number;
}

export interface MetricsResponse {
  health: HealthStatus;
  algorithm: AlgorithmStats;
  trading: TradingStats;
  database: DatabaseStats;
}

// From GET /engine-status — the backend's own answer to "is today a trading day, and did
// today's Job 1 / Job 2 actually run," computed from Alpaca's Clock+Calendar APIs and
// scheduler_state. See lib/engine-status.ts for how the dashboard turns this into a display state.
export interface EngineStatus {
  today: string;
  is_trading_day: boolean;
  job1_last_success_date: string | null;
  job2_last_run_date: string | null;
}
