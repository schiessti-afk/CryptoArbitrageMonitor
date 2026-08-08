# Crypto Arbitrage Monitor — Product brief

> **Historical.** This document is the original product brief. Shipped V1 differs in several places
> (five venues including Bitget/KuCoin, **50** USD+USDT markets, selective polling, Docker Compose
> full stack). For current behavior use [README.md](../README.md), [ARCHITECTURE.md](./ARCHITECTURE.md),
> and [sprints/SPRINT.md](./sprints/SPRINT.md).

## 1. Project Overview

Crypto Arbitrage Monitor is an open-source full-stack application that monitors cryptocurrency prices across multiple public exchange APIs and identifies potential arbitrage opportunities.

The application is primarily a technical showcase demonstrating modern Java/Spring Boot and Angular development rather than a production trading system.

The system:

- Fetches public market prices from multiple cryptocurrency exchanges.
- Normalizes exchange-specific API responses into a common internal model.
- Compares bid/ask prices between exchanges.
- Calculates estimated net arbitrage spreads after trading fees.
- Streams live opportunities to an Angular dashboard using Spring WebSocket, STOMP and SockJS.
- Stores tracked pairs, exchange fees and historical spread data in PostgreSQL.
- Runs completely with public APIs and requires no private exchange API keys.
- Can be started locally using Docker Compose.

The application does not execute trades.

> **Important:** Detected opportunities are estimates only. They do not account for every real-world trading factor such as order-book depth, slippage, withdrawal fees, blockchain confirmation time or execution latency.

## 2. Project Goals

The main goal is to create a compact but professionally structured portfolio project demonstrating:

### Backend

- Java 17+
- Spring Boot 3
- Spring WebClient
- Asynchronous external API communication
- REST APIs
- Spring WebSocket
- STOMP
- SockJS
- Spring Data JPA
- PostgreSQL
- Flyway database migrations
- Scheduled/background processing
- Exception handling
- Logging
- Unit and integration testing

### Frontend

- Angular
- TypeScript
- RxJS
- Angular Signals where appropriate
- WebSocket/STOMP communication
- REST API integration
- Responsive dashboard UI

### DevOps

- Docker
- Docker Compose
- Multi-container application
- Environment-based configuration

## 3. Scope

The first version intentionally has a limited scope.

### Supported Exchanges

The initial implementation will support:

- Binance
- Kraken
- Coinbase

Only public market-data APIs should be used.

- No exchange account is required.
- No private API keys are required.

The implementation should prefer official, free public APIs that provide the required bid/ask market data.

If an exchange changes or restricts its public API, its implementation should be isolated inside its exchange adapter so that the rest of the application remains unaffected.

## 4. Supported Trading Pairs

V1 will initially monitor only:

- BTC/USDT
- ETH/USDT

The system should not allow arbitrary user-entered symbols in V1.

Additional pairs can be added later through configuration or a future feature.

This keeps the initial project focused and avoids unnecessary complexity around symbol validation and exchange-specific trading-pair availability.

## 5. What the Application Does

The basic flow is:

```
                    Public Exchange APIs
                   /        |        \
                  /         |         \
             Binance      Kraken    Coinbase
                 \          |          /
                  \         |         /
                   Exchange Adapters
                          |
                          v
                 Normalized Price Data
                          |
                          v
                Spread Calculation Engine
                          |
                +---------+---------+
                |                   |
                v                   v
          PostgreSQL          WebSocket/STOMP
                |                   |
                |                   v
                |               Angular
                |              Dashboard
                |
                v
          Historical Data
```

## 6. Exchange Adapter Architecture

Each exchange has a different API format.

The application should therefore use an abstraction layer.

Example:

```java
public interface ExchangeAdapter {

    Exchange getExchange();

    Mono<PriceTicker> getTicker(String symbol);
}
```

Implementations:

```
ExchangeAdapter
    |
    +-- BinanceAdapter
    |
    +-- KrakenAdapter
    |
    +-- CoinbaseAdapter
```

The exchange-specific adapters are responsible for:

- Calling the exchange API.
- Handling the exchange's response format.
- Converting the response into the application's common `PriceTicker` model.
- Handling exchange-specific symbol formats.
- Reporting exchange-specific API errors.

The rest of the application should not need to know how Binance, Kraken or Coinbase structures its API responses.

This makes adding another exchange straightforward.

## 7. Market Data

The application will retrieve the best available public bid and ask prices.

For each exchange and trading pair:

- **Bid Price** = highest price currently offered by buyers
- **Ask Price** = lowest price currently offered by sellers

The application should use:

- **Ask price** → potential BUY price
- **Bid price** → potential SELL price

This is more realistic than simply comparing a single ticker price.

Example:

**Binance BTC/USDT**

| | |
|---|---|
| Bid | 67,100 |
| Ask | 67,120 |

**Kraken BTC/USDT**

| | |
|---|---|
| Bid | 67,400 |
| Ask | 67,420 |

Potential arbitrage:

- **BUY** → Binance at 67,120
- **SELL** → Kraken at 67,400

## 8. Polling Strategy

V1 will use scheduled REST polling.

The backend periodically requests market data from the supported exchanges.

**Target interval:** Every 3 seconds

The implementation should use Spring's scheduling capabilities together with WebClient.

The calls to the exchanges should be performed asynchronously rather than sequentially.

Conceptually:

```
Every 3 seconds
        |
        +---- Binance request
        |
        +---- Kraken request
        |
        +---- Coinbase request
                    |
                    v
             Collect responses
                    |
                    v
             Calculate spreads
```

The exact implementation should avoid unnecessarily blocking threads while waiting for external APIs.

## 9. Spread Calculation

The application should calculate potential arbitrage opportunities using the ask price on the buying exchange and the bid price on the selling exchange.

For example:

- **Buy on Binance:** Ask = $67,120
- **Sell on Kraken:** Bid = $67,400

Raw spread:

```
Raw Spread % = ((Sell Price / Buy Price) - 1) × 100
```

Result: **0.417%**

## 10. Trading Fees

Exchange fees are included in the calculation.

The default configuration can use representative taker fees, but these values must be clearly marked as configurable estimates rather than guaranteed current exchange fees.

Each exchange should have:

- Buy Taker Fee
- Sell Taker Fee

The calculation should account for both sides of the transaction.

Conceptually:

```
Effective Buy Cost    = Buy Price × (1 + Buy Fee)

Effective Sell Revenue = Sell Price × (1 - Sell Fee)

Net Spread % = (Effective Sell Revenue / Effective Buy Cost - 1) × 100
```

The application should display both:

- Raw Spread
- Net Spread

## 11. Estimated Profit

The dashboard should optionally show the estimated result for a fixed hypothetical investment amount.

Example:

| | |
|---|---|
| Investment | $1,000 |
| Estimated Buy Cost | $1,000 |
| Estimated Sell Revenue | $1,003.20 |
| Estimated Profit | $3.20 |
| Estimated Net Return | 0.32% |

This is only a calculation.

The application does not execute the transaction.

The UI should clearly communicate that the value is an estimate.

## 12. Important Real-World Limitations

The application should explicitly acknowledge that a displayed arbitrage opportunity does not necessarily mean a profitable executable trade.

V1 does not model:

- Order-book depth
- Slippage
- Withdrawal fees
- Blockchain/network fees
- Deposit fees
- Transfer delays
- Exchange withdrawal restrictions
- Market movement during execution
- Account-specific trading fees
- Minimum order sizes
- Liquidity constraints
- Execution failure

Therefore the application should use terminology such as:

- **Potential Arbitrage Opportunity**

rather than:

- **Guaranteed Profit**

## 13. Real-Time Frontend Updates

The Angular frontend should not continuously poll the backend.

Instead:

```
Exchange APIs
      |
      v
Backend calculation
      |
      v
Spring WebSocket
      |
      v
STOMP
      |
      v
SockJS
      |
      v
Angular
```

The backend publishes calculated spread updates to a WebSocket topic.

Example: `/topic/spreads`

Angular subscribes to the topic and updates the dashboard immediately when new data arrives.

This gives the project practical experience with:

- WebSockets
- STOMP messaging
- SockJS
- Reactive frontend updates
- Server push

## 14. Why STOMP and SockJS?

STOMP provides a structured messaging protocol on top of WebSocket.

Instead of manually defining a custom message protocol, the application can use concepts such as:

- `/topic/spreads`
- `SUBSCRIBE`
- `SEND`

SockJS provides fallback transport mechanisms for environments where native WebSocket connectivity may not be available or reliable.

The purpose of using them in this project is also educational:

Demonstrate how a Spring Boot backend can publish real-time application events to an Angular frontend using WebSocket messaging.

## 15. Database

PostgreSQL will be used intentionally to demonstrate relational database integration.

The database will store:

### Tracked Pairs

Example schema:

```
tracked_pair
----------------
id
symbol
base_currency
quote_currency
active
created_at
```

V1 contains:

- BTC/USDT
- ETH/USDT

### Exchange Fees

Example schema:

```
exchange_fee
----------------
id
exchange
taker_fee
updated_at
```

This allows fees to be changed without modifying application code.

### Historical Spread Logs

Spread opportunities should be persisted for later analysis.

Example schema:

```
spread_log
----------------
id
symbol
buy_exchange
sell_exchange
buy_price
sell_price
raw_spread_percent
net_spread_percent
estimated_profit
calculated_at
```

The system should avoid storing every individual API response.

Instead, it stores the calculated spread result.

This keeps the database focused on useful application data rather than raw market-data duplication.

## 16. Historical Data

Historical spread data makes PostgreSQL useful beyond simple configuration storage.

The application can eventually provide basic analytics such as:

| Metric | Value |
|---|---|
| Pair | BTC/USDT |
| Highest Net Spread | 1.24% |
| Average Net Spread | 0.18% |
| Opportunities Detected | 1,245 |

However, complex analytics and charting are not required for V1.

The initial goal is simply to demonstrate that calculated opportunities are persisted correctly.

## 17. REST API

The backend should expose a small REST API.

Example endpoints:

| Method | Endpoint |
|---|---|
| GET | `/api/pairs` |
| GET | `/api/exchanges` |
| GET | `/api/fees` |
| GET | `/api/spreads/latest` |
| GET | `/api/spreads/history` |

The REST API should be kept intentionally small.

The frontend uses REST for:

- Initial application data
- Configuration
- Historical data

The frontend uses WebSocket for:

- Live spread updates

## 18. Frontend Dashboard

The main screen should be a simple monitoring dashboard.

Example:

```
========================================================
              CRYPTO ARBITRAGE MONITOR
========================================================

Status: ● LIVE

BTC/USDT

BUY                 SELL
Binance              Kraken
$67,120.00           $67,400.00

Raw Spread:          0.417%
Net Spread:          0.154%
Estimated Profit:    $1.54 / $1,000


--------------------------------------------------------

ETH/USDT

BUY                 SELL
Coinbase             Binance
$3,420.00            $3,435.00

Raw Spread:          0.439%
Net Spread:          0.186%
Estimated Profit:    $1.86 / $1,000

========================================================
```

The interface should prioritize:

- Clear numbers
- Exchange names
- Buy/sell direction
- Net spread
- Connection status
- Last update timestamp

The UI does not need to look like a professional trading terminal.

## 19. Frontend Technology

Angular will be used with:

- TypeScript
- RxJS
- Angular Signals where useful
- STOMP client
- SockJS
- REST services
- Responsive CSS framework such as Tailwind CSS

The frontend should have a small number of components.

Suggested structure:

```
app/
├── components/
│   ├── dashboard/
│   ├── spread-table/
│   ├── spread-detail/
│   └── connection-status/
│
├── services/
│   ├── spread.service.ts
│   ├── websocket.service.ts
│   └── exchange.service.ts
│
└── models/
    ├── spread.model.ts
    ├── exchange.model.ts
    └── price-ticker.model.ts
```

## 20. Backend Architecture

The backend should remain a modular monolith.

No microservices are required.

Suggested architecture:

| Package | Purpose |
|---|---|
| `controller/` | REST controllers |
| `service/` | Business logic |
| `exchange/` | Exchange adapters |
| `repository/` | Database access |
| `model/` | Entities |
| `dto/` | API/WebSocket DTOs |
| `config/` | Application configuration |

Example:

```
backend/src/main/java/com/example/arbitrage/

├── config/
├── controller/
├── dto/
├── exchange/
│   ├── ExchangeAdapter.java
│   ├── BinanceAdapter.java
│   ├── KrakenAdapter.java
│   └── CoinbaseAdapter.java
├── model/
├── repository/
└── service/
```

## 21. Error Handling

External APIs can fail.

The application must not stop monitoring all exchanges because one exchange becomes unavailable.

Example:

| Exchange | Status |
|---|---|
| Binance | ✓ |
| Kraken | ✓ |
| Coinbase | ✗ API timeout |

The application should continue operating with Binance + Kraken and report Coinbase as temporarily unavailable.

The frontend should show exchange connection status.

## 22. Docker

The entire application should run with:

```bash
docker compose up
```

Services:

- PostgreSQL
- Spring Boot Backend
- Angular Frontend

Example `docker-compose.yml`:

```yaml
services:
  postgres
  backend
  frontend
```

The application should not require the user to manually install PostgreSQL or configure the database.

## 23. Configuration

Sensitive or environment-specific configuration should not be hard-coded.

Example environment variables:

- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`

Exchange API URLs should be configurable through Spring configuration.

Although no private API keys are required, configuration should still be structured as if additional exchanges or authenticated services could be introduced later.

## 24. Database Migrations

Flyway should manage database schema changes.

Example:

```
db/migration/
├── V1__init_schema.sql
└── V2__add_spread_indexes.sql
```

The application should never rely on manually creating database tables.

## 25. Testing

The project should contain meaningful tests rather than trying to maximize test count.

### Unit Tests

Focus on: `SpreadCalculationService`

Test cases:

- Positive spread
- Negative spread
- Fees
- Equal prices
- Different buy/sell fees
- Very small spreads
- Invalid prices

### Adapter Tests

Verify that exchange-specific API responses are correctly converted into `PriceTicker`.

### Integration Tests

At least one integration test should verify:

```
Spring Boot
    ↓
PostgreSQL
    ↓
Repository
```

The goal is to demonstrate testing knowledge without turning the project into a testing framework showcase.

## 26. Non-Goals

The following are explicitly outside the initial project scope:

- Executing trades
- Private exchange API authentication
- User accounts
- Portfolio management
- Deposits/withdrawals
- Automated trading bots
- Trading strategies
- AI predictions
- Machine learning
- Redis
- Kafka
- Microservices
- Kubernetes
- Cloud deployment
- Mobile application
- Complex technical indicators
- Advanced charting
- Arbitrage execution automation

These can be considered future ideas but should not be added simply to increase project size.

## 27. Future Extensions

Possible future improvements include:

### More Exchanges

- OKX
- Bybit
- Bitstamp
- Bitfinex

Only if their free public APIs provide suitable market data.

### More Trading Pairs

- SOL/USDT
- XRP/USDT
- ADA/USDT

### Advanced Spread Analytics

- Historical charts
- Best exchange combinations
- Opportunity frequency
- Average spread
- Maximum spread
- Spread duration

### Better Market Realism

- Order-book depth
- Slippage estimation
- Minimum order sizes
- Withdrawal fees
- Network fees

### Notifications

Potential opportunities could trigger:

- Browser notification
- Email
- Telegram

These are future features and not part of V1.

## 28. V1 Definition of Done

V1 is complete when:

- [ ] Spring Boot application starts successfully.
- [ ] PostgreSQL starts through Docker Compose.
- [ ] Flyway creates the required schema.
- [ ] Binance public API integration works.
- [ ] Kraken public API integration works.
- [ ] Coinbase public API integration works.
- [ ] Exchange responses are normalized through `ExchangeAdapter`.
- [ ] BTC/USDT is monitored.
- [ ] ETH/USDT is monitored.
- [ ] Prices are refreshed approximately every 3 seconds.
- [ ] Buy/sell combinations are calculated.
- [ ] Raw spread is calculated.
- [ ] Trading fees are included.
- [ ] Net spread is calculated.
- [ ] Estimated profit is calculated.
- [ ] Spread results are persisted.
- [ ] Spring WebSocket is configured.
- [ ] STOMP messaging works.
- [ ] SockJS is configured.
- [ ] Angular receives live spread updates.
- [ ] Dashboard displays current opportunities.
- [ ] Exchange connection failures are handled gracefully.
- [ ] REST endpoints provide initial/historical data.
- [ ] Unit tests cover the calculation engine.
- [ ] Integration tests cover database functionality.
- [ ] Entire application starts with `docker compose up`
