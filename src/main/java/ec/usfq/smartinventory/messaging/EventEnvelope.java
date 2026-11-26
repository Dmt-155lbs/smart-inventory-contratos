package ec.usfq.smartinventory.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sobre comun de todos los eventos. Su forma esta fijada por contracts/schemas/*.schema.json:
 * schemaVersion es un semver en string ("1.0.0"), correlationId un UUID y producer el servicio emisor.
 */
public record EventEnvelope(UUID eventId, String eventType, String schemaVersion, Instant occurredAt,
                            String correlationId, String producer, Map<String, Object> payload) {
}
