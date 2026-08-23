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
