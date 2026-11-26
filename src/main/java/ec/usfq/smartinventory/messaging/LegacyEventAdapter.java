package ec.usfq.smartinventory.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Convierte el sobre v1 (contrato vigente) al sobre anterior para publicarlo con la routing key legada durante la
 * migracion: schemaVersion entero 1, producer "smart-inventory", eventType = routing key anterior y, para
 * stock-reserved, el payload anterior (quantity / lines[lotCode, quantity]). Se elimina junto con la clave legada.
 */
@Component
public class LegacyEventAdapter {

    private final ObjectMapper mapper;

    public LegacyEventAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @return JSON del mensaje legado, o el mismo JSON si el evento no tiene forma legada distinta */
    public String toLegacy(DomainEvent event, String legacyRoutingKey, String envelopeJson) {
        try {
            ObjectNode env = (ObjectNode) mapper.readTree(envelopeJson);
            env.put("eventType", legacyRoutingKey);
            env.put("schemaVersion", 1);
            env.put("producer", "smart-inventory");
            if (event == DomainEvent.STOCK_RESERVED) {
                env.set("payload", legacyStockReserved((ObjectNode) env.get("payload")));
            }
            return mapper.writeValueAsString(env);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build legacy message for " + event, e);
        }
    }

    private ObjectNode legacyStockReserved(ObjectNode p) {
        ObjectNode legacy = mapper.createObjectNode();
        legacy.set("reservationId", p.get("reservationId"));
        legacy.set("sku", p.get("sku"));
        legacy.put("location", text(p, "location"));
        legacy.set("quantity", p.get("qty"));
        legacy.put("destination", text(p, "destination"));
        ArrayNode lines = legacy.putArray("lines");
        for (JsonNode lot : p.withArray("lots")) {
            lines.addObject().put("lotCode", lot.get("lotId").asText()).set("quantity", lot.get("qty"));
        }
        return legacy;
    }

    private static String text(ObjectNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : "";
    }
}
