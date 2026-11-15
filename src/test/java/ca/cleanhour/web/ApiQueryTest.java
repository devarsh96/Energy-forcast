package ca.cleanhour.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiQueryTest {

    @Test
    void rangeIsLimitedTo100DaysInclusive() {
        assertDoesNotThrow(() -> ApiController.Query.of("2026-08-01", "2026-11-08", null)); // 100 days
        assertThrows(IllegalArgumentException.class, () -> ApiController.Query.of("2026-08-01", "2026-11-09", null)); // 101 days
    }
}
