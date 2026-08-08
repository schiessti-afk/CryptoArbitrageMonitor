package com.cryptoarbitrage.monitor.exchange;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class AdapterJsonUtils {

    private AdapterJsonUtils() {
    }

    static BigDecimal optionalDecimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String text = node.get(field).asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text);
    }

    static BigDecimal optionalDecimalFromArray(JsonNode array, int index) {
        if (array == null || !array.isArray() || array.size() <= index || array.get(index).isNull()) {
            return null;
        }
        String text = array.get(index).asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text);
    }

    static List<OrderBookLevel> parseLevels(JsonNode levels, int maxDepth) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }
        int limit = Math.min(maxDepth, levels.size());
        for (int i = 0; i < limit; i++) {
            JsonNode level = levels.get(i);
            if (level == null || !level.isArray() || level.size() < 2) {
                continue;
            }
            BigDecimal price = new BigDecimal(level.get(0).asText());
            BigDecimal size = new BigDecimal(level.get(1).asText());
            if (price.signum() > 0 && size.signum() > 0) {
                result.add(new OrderBookLevel(price, size));
            }
        }
        return result;
    }
}
