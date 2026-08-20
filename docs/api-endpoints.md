# API Endpoints

| Method | Path | Description | Request Body | Response |
|---|---|---|---|---|
| GET | /:userId/account | Get user's cash balance and buying power (fetched live from Alpaca using user's key) | none | `{ cash, buying_power, portfolio_value }` |
| GET | /:userId/positions | Get user's current stock positions (fetched live from Alpaca) | none | `[ { symbol, qty, avg_entry_price, current_price, unrealized_pl } ]` |
| GET | /recommendations/snp500 | Get this week's BUY/SELL/HOLD for S&P 500 stocks | none | `[ { symbol, name, momentum_score, action, week_date } ]` |
| GET | /recommendations/snp400 | Get this week's BUY/SELL/HOLD for S&P 400 stocks | none | `[ { symbol, name, momentum_score, action, week_date } ]` |
| GET | /recommendations/snp600 | Get this week's BUY/SELL/HOLD for S&P 600 stocks | none | `[ { symbol, name, momentum_score, action, week_date } ]` |
| GET | /recommendations/nasdaq100 | Get this week's BUY/SELL/HOLD for Nasdaq 100 stocks | none | `[ { symbol, name, momentum_score, action, week_date } ]` |
| POST | /:userId/trade/buy | Buy stocks. System reads this week's BUY recommendations, divides amount equally, places orders via Alpaca using user's key | `{ amount: number }` | `{ trades: [ { symbol, amount_invested, shares_bought, price } ] }` |
| POST | /:userId/trade/sell | Sell stocks. System reads SELL recommendations, checks what user holds on Alpaca, sells matching positions | none | `{ trades: [ { symbol, shares_sold, amount_received } ] }` |
| GET | /:userId/trades | Get user's trade history from our TRADE table | none | `[ { symbol, action, amount, price_per_share, quantity, traded_at } ]` |

Notes:

- All `:userId` endpoints require the controller to verify the `userId` exists in the USER table before processing.
- Authentication is handled by Supabase Auth. There are no `/auth/register` or `/auth/login` endpoints in this API.
- There is no `POST /wallet/add-funds` endpoint. Alpaca paper accounts start with $100,000 automatically.
