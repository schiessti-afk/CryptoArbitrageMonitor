-- V2: Indexes for spread_log queries

CREATE INDEX idx_spread_log_symbol_calculated_at
ON spread_log (symbol, calculated_at DESC);
