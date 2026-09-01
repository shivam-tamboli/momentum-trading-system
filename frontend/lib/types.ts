export interface AccountResponse {
  cash: number;
  buying_power: number;
  portfolio_value: number;
}

export interface Position {
  symbol: string;
  qty: number;
  avg_entry_price: number;
  current_price: number;
  unrealized_pl: number;
}

export interface Recommendation {
  symbol: string;
  name: string;
  momentum_score: number;
  action: 'BUY' | 'SELL' | 'HOLD';
  week_date: string;
}

export interface TradeHistoryItem {
  symbol: string;
  action: 'BUY' | 'SELL';
  amount: number;
  price_per_share: number;
  quantity: number;
  traded_at: string;
}

export interface BuyRequest {
  amount: number;
}

export interface BuyTradeResult {
  symbol: string;
  amount_invested: number;
  shares_bought: number;
  price: number;
}

export interface BuyResponse {
  trades: BuyTradeResult[];
}

export interface ErrorResponse {
  error: string;
}

export interface SellTradeResult {
  symbol: string;
  shares_sold: number;
  amount_received: number;
}

export interface SellResponse {
  trades: SellTradeResult[];
  message?: string;
}

export interface MeResponse {
  id: number;
  email: string;
}

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
