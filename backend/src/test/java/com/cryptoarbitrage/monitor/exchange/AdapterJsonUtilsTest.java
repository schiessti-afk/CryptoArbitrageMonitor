package com.cryptoarbitrage.monitor.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdapterJsonUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void optionalDecimal_handlesMissingNullBlankAndValid() throws Exception {
        assertNull(AdapterJsonUtils.optionalDecimal(null, "volume"));
        assertNull(AdapterJsonUtils.optionalDecimal(MAPPER.readTree("{}"), "volume"));
        assertNull(AdapterJsonUtils.optionalDecimal(MAPPER.readTree("{\"volume\":null}"), "volume"));
        assertNull(AdapterJsonUtils.optionalDecimal(MAPPER.readTree("{\"volume\":\"  \"}"), "volume"));
        assertEquals(0, new BigDecimal("12.5")
                .compareTo(AdapterJsonUtils.optionalDecimal(MAPPER.readTree("{\"volume\":\"12.5\"}"), "volume")));
    }

    @Test
    void optionalDecimalFromArray_handlesBadIndexAndValid() throws Exception {
        JsonNode array = MAPPER.readTree("[\"10.1\", null, \"\"]");
        assertNull(AdapterJsonUtils.optionalDecimalFromArray(null, 0));
        assertNull(AdapterJsonUtils.optionalDecimalFromArray(MAPPER.readTree("{}"), 0));
        assertNull(AdapterJsonUtils.optionalDecimalFromArray(array, 5));
        assertNull(AdapterJsonUtils.optionalDecimalFromArray(array, 1));
        assertNull(AdapterJsonUtils.optionalDecimalFromArray(array, 2));
        assertEquals(0, new BigDecimal("10.1").compareTo(AdapterJsonUtils.optionalDecimalFromArray(array, 0)));
    }

    @Test
    void parseLevels_skipsInvalidRowsAndHonorsMaxDepth() throws Exception {
        JsonNode levels = MAPPER.readTree("""
                [
                  ["100", "1.5"],
                  ["0", "2"],
                  ["101", "-1"],
                  ["102"],
                  "bad",
                  ["103", "0.25"],
                  ["104", "0.5"]
                ]
                """);

        // maxDepth limits how many source rows are inspected, not how many valid levels are kept.
        List<OrderBookLevel> shallow = AdapterJsonUtils.parseLevels(levels, 2);
        assertEquals(1, shallow.size());
        assertEquals(new BigDecimal("100"), shallow.get(0).price());
        assertEquals(new BigDecimal("1.5"), shallow.get(0).size());

        List<OrderBookLevel> parsed = AdapterJsonUtils.parseLevels(levels, 7);
        assertEquals(3, parsed.size());
        assertEquals(new BigDecimal("103"), parsed.get(1).price());
        assertEquals(new BigDecimal("104"), parsed.get(2).price());
    }

    @Test
    void parseLevels_nonArrayReturnsEmpty() throws Exception {
        assertTrue(AdapterJsonUtils.parseLevels(null, 5).isEmpty());
        assertTrue(AdapterJsonUtils.parseLevels(MAPPER.readTree("{}"), 5).isEmpty());
    }
}
