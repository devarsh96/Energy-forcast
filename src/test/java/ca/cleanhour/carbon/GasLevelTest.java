package ca.cleanhour.carbon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GasLevelTest {

    @Test
    void dailyRampAloneIsNotHigh() {
        // 28% of supply at the top of this week's range: moderate, not high.
        assertEquals("moderate", CarbonService.gasLevel(0.28, 1.0));
    }

    @Test
    void highNeedsBothLargeShareAndTopOfWeek() {
        assertEquals("high", CarbonService.gasLevel(0.34, 0.85));
        assertEquals("moderate", CarbonService.gasLevel(0.34, 0.6));
    }

    @Test
    void smallShareOrNearWeeklyLowIsLow() {
        assertEquals("low", CarbonService.gasLevel(0.10, 0.9));
        assertEquals("low", CarbonService.gasLevel(0.25, 0.1));
    }
}
