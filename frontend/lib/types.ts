export interface AccountResponse {
  cash: number;
  buying_power: number;
  portfolio_value: number;
}

export interface ErrorResponse {
  error: string;
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
