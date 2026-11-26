package ec.usfq.smartinventory.application;

import ec.usfq.smartinventory.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unico lugar que arma los payloads de los eventos publicos; sus claves son las de los JSON Schema en
 * contracts/schemas/ (EventContractTest valida lo que produce).
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /** inventory.stock-reserved.v1 */
    public static Map<String, Object> stockReserved(UUID reservationId, String sku, String location, int qty,
                                                    String destination, List<ReservationService.Line> lines) {
        return Map.of(
                "reservationId", reservationId.toString(), "sku", sku, "qty", qty,
                "lots", lines.stream().map(l -> Map.of("lotId", l.lotCode(), "qty", l.quantity(),
                        "expiresAt", l.expiresOn().toString())).toList(),
                "location", location, "destination", destination);
    }

    /** replenishment.risk-detected.v1 */
    public static Map<String, Object> riskDetected(UUID suggestionId, String sku, int availableQty, int reorderPoint,
                                                   int suggestedQty, String supplier, Instant detectedAt) {
        return Map.of("suggestionId", suggestionId.toString(), "sku", sku, "availableQty", availableQty,
                "reorderPoint", reorderPoint, "suggestedQty", suggestedQty, "supplier", supplier,
                "riskLevel", RiskLevel.of(availableQty, reorderPoint).name(), "detectedAt", detectedAt.toString());
    }
}
