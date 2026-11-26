package ec.usfq.smartinventory.domain;

/** Severidad del riesgo de desabastecimiento (campo riskLevel del evento replenishment.risk-detected). */
public enum RiskLevel {
    MEDIUM, HIGH, CRITICAL;

    /** CRITICAL: sin disponible; HIGH: disponible <= mitad del punto de reorden; MEDIUM: el resto bajo el punto. */
    public static RiskLevel of(int available, int reorderPoint) {
        if (available <= 0) {
            return CRITICAL;
        }
        return available * 2 <= reorderPoint ? HIGH : MEDIUM;
    }
}
