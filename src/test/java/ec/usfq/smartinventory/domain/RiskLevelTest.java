package ec.usfq.smartinventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RiskLevelTest {

    @Test
    void criticalWhenNothingAvailable() {
        assertEquals(RiskLevel.CRITICAL, RiskLevel.of(0, 60));
    }

    @Test
    void highAtOrBelowHalfOfReorderPoint() {
        assertEquals(RiskLevel.HIGH, RiskLevel.of(30, 60));
        assertEquals(RiskLevel.HIGH, RiskLevel.of(1, 60));
    }

    @Test
    void mediumBetweenHalfAndReorderPoint() {
        assertEquals(RiskLevel.MEDIUM, RiskLevel.of(31, 60));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.of(59, 60));
    }
}
