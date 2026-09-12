// Turns the backend's engine-status snapshot (GET /engine-status) into the three-way display
// state every "Today"-labeled dashboard element needs. Introduced because each of those elements
// used to guess this from raw scored_at/traded_at timestamps via calendar-day or elapsed-time
// heuristics — both of which read a stale Friday run as "live" all through Saturday morning,
// since neither one knows whether today is actually a trading day. This is the single place that
// answers that question, from the one source that actually knows: scheduler_state via the
// backend, not client-side date math.
import type { EngineStatus } from './types';

export type ScoringState = 'live' | 'pending' | 'market-closed';

export function getScoringState(status: EngineStatus): ScoringState {
  if (!status.is_trading_day) return 'market-closed';
  return status.job1_last_success_date === status.today ? 'live' : 'pending';
}

export type TradingState = 'completed' | 'pending' | 'market-closed';

export function getTradingState(status: EngineStatus): TradingState {
  if (!status.is_trading_day) return 'market-closed';
  return status.job2_last_run_date === status.today ? 'completed' : 'pending';
}
