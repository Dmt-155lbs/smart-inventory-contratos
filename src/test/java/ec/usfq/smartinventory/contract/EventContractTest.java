package ec.usfq.smartinventory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import ec.usfq.smartinventory.application.EventPayloads;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.messaging.DomainEvent;
import ec.usfq.smartinventory.messaging.LegacyEventAdapter;
import ec.usfq.smartinventory.messaging.OutboxWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contrato de eventos: lo que OutboxWriter realmente serializa (con el ObjectMapper de Spring Boot, el mismo de
 * produccion) debe cumplir los JSON Schema 2020-12 de contracts/schemas/. Tambien se validan los ejemplos:
 * los validos pasan y los invalidos DEBEN fallar. Corre sin BD ni broker.
 */
@JsonTest
class EventContractTest {

    static final Path SCHEMAS = Path.of("contracts/schemas");
    static final Path EXAMPLES = Path.of("contracts/examples");

    @Autowired ObjectMapper mapper;

    // ---- infraestructura de prueba -------------------------------------------------------------

    /** JdbcTemplate que captura el INSERT del outbox en lugar de ejecutarlo. */
    static final class CapturingJdbc extends JdbcTemplate {
        final List<Object[]> inserts = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            inserts.add(args);
            return 1;
        }
    }

    static JsonSchema schema(String file) {
        SchemaValidatorsConfig cfg = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(com.networknt.schema.SchemaLocation.of(SCHEMAS.resolve(file).toAbsolutePath().toUri().toString()), cfg);
    }

    static final String STOCK_RESERVED_SCHEMA = "stock-reserved.v1.schema.json";
    static final String RISK_SCHEMA = "replenishment-risk-detected.v1.schema.json";

    private JsonNode envelopeWrittenBy(DomainEvent event, String correlationId, java.util.Map<String, Object> payload) throws IOException {
        CapturingJdbc jdbc = new CapturingJdbc();
        var writer = new OutboxWriter(jdbc, mapper, Clock.fixed(Instant.parse("2026-11-20T14:03:11.250Z"), ZoneOffset.UTC));
        writer.append(event, correlationId, payload);
        // args: eventId, eventType, routingKey, payloadJson, correlationId, occurredAt
        Object[] args = jdbc.inserts.get(0);
        assertEquals(event.eventType(), args[1]);
        assertEquals(event.routingKey(), args[2]);
        return mapper.readTree((String) args[3]);
    }

    private static void assertValid(JsonSchema schema, JsonNode doc) {
        Set<ValidationMessage> errors = schema.validate(doc);
        assertTrue(errors.isEmpty(), "El documento incumple el contrato: " + errors + "\n" + doc);
    }

    private static Set<ValidationMessage> errorsOf(JsonSchema schema, JsonNode doc) {
        return schema.validate(doc);
    }

    // ---- sobres reales producidos por OutboxWriter ----------------------------------------------

    @Test
    void stockReservedEnvelopeProducedByOutboxWriterMatchesSchema() throws Exception {
        var lines = List.of(new ReservationService.Line("L-B", LocalDate.parse("2026-12-01"), 30),
                new ReservationService.Line("L-A", LocalDate.parse("2026-12-15"), 5));
        var payload = EventPayloads.stockReserved(UUID.randomUUID(), "SKU-1001", "BOD-UIO", 35, "PEDIDO-7788", lines);
        JsonNode env = envelopeWrittenBy(DomainEvent.STOCK_RESERVED, UUID.randomUUID().toString(), payload);

        assertValid(schema(STOCK_RESERVED_SCHEMA), env);
        assertEquals("1.0.0", env.get("schemaVersion").asText());
        assertTrue(env.get("schemaVersion").isTextual(), "schemaVersion debe ser string semver, no numero");
        assertEquals("inventory-service", env.get("producer").asText());
        assertEquals("2026-11-20T14:03:11.250Z", env.get("occurredAt").asText());
        assertEquals(35, env.get("payload").get("qty").asInt());
        assertEquals("L-B", env.get("payload").get("lots").get(0).get("lotId").asText());
    }

    @Test
    void riskDetectedEnvelopeProducedByOutboxWriterMatchesSchemaForEveryRiskLevel() throws Exception {
        for (int[] c : new int[][]{{40, 60}, {20, 60}, {0, 60}}) {   // MEDIUM, HIGH, CRITICAL
            var payload = EventPayloads.riskDetected(UUID.randomUUID(), "SKU-1001", c[0], c[1], 260, "PROV-ANDINA",
                    Instant.parse("2026-11-20T14:03:12Z"));
            JsonNode env = envelopeWrittenBy(DomainEvent.RISK_DETECTED, UUID.randomUUID().toString(), payload);
            assertValid(schema(RISK_SCHEMA), env);
            assertEquals("replenishment-service", env.get("producer").asText());
        }
        var critical = EventPayloads.riskDetected(UUID.randomUUID(), "S", 0, 60, 300, "P", Instant.now());
        assertEquals("CRITICAL", critical.get("riskLevel"));
    }

    @Test
    void nonUuidCorrelationIdIsReplacedSoTheEnvelopeStaysValid() throws Exception {
        var payload = EventPayloads.stockReserved(UUID.randomUUID(), "SKU-1", "BOD", 1, "X",
                List.of(new ReservationService.Line("L", LocalDate.parse("2027-01-01"), 1)));
        assertValid(schema(STOCK_RESERVED_SCHEMA), envelopeWrittenBy(DomainEvent.STOCK_RESERVED, "req-123", payload));
        assertValid(schema(STOCK_RESERVED_SCHEMA), envelopeWrittenBy(DomainEvent.STOCK_RESERVED, null, payload));
    }

    // ---- ejemplos --------------------------------------------------------------------------------

    private JsonNode read(Path p) throws IOException {
        return mapper.readTree(Files.readString(p));
    }

    @Test
    void validExamplesPassTheirSchema() throws Exception {
        assertValid(schema(STOCK_RESERVED_SCHEMA), read(EXAMPLES.resolve("stock-reserved.v1.json")));
        assertValid(schema(RISK_SCHEMA), read(EXAMPLES.resolve("replenishment-risk-detected.v1.json")));
    }

    @Test
    void minorVersionWithExtraOptionalFieldsStillValidatesAgainstV1() throws Exception {
        JsonNode v11 = read(EXAMPLES.resolve("stock-reserved.v1.1.compatible.json"));
        assertEquals("1.1.0", v11.get("schemaVersion").asText());
        assertValid(schema(STOCK_RESERVED_SCHEMA), v11);   // payload abierto: un consumidor v1 lo acepta
    }

    @Test
    void invalidExamplesAreRejected() throws Exception {
        var e1 = errorsOf(schema(STOCK_RESERVED_SCHEMA), read(EXAMPLES.resolve("invalid/stock-reserved.v1.invalid.json")));
        assertFalse(e1.isEmpty(), "el ejemplo invalido de stock-reserved debe fallar");
        String all1 = e1.toString();
        assertTrue(all1.contains("qty"), "debe detectar la falta de qty: " + all1);
        assertTrue(all1.contains("schemaVersion"), all1);
        assertTrue(all1.contains("correlationId"), all1);
        assertTrue(all1.contains("producer"), all1);

        var e2 = errorsOf(schema(RISK_SCHEMA), read(EXAMPLES.resolve("invalid/replenishment-risk-detected.v1.invalid.json")));
        assertFalse(e2.isEmpty(), "el ejemplo invalido de risk-detected debe fallar");
        String all2 = e2.toString();
        assertTrue(all2.contains("unexpectedEnvelopeField"), "sobre estricto: " + all2);
        assertTrue(all2.contains("riskLevel"), all2);
        assertTrue(all2.contains("suggestedQty"), all2);
    }

    @Test
    void renamingARequiredFieldIsDetectedAsABreakingChange() throws Exception {
        // Simula el cambio incompatible clasico (qty -> quantity) sobre un evento valido: el contrato v1 lo rechaza
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) read(EXAMPLES.resolve("stock-reserved.v1.json"));
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) node.get("payload");
        payload.set("quantity", payload.remove("qty"));
        assertFalse(errorsOf(schema(STOCK_RESERVED_SCHEMA), node).isEmpty());
    }

    @Test
    void legacyCopyKeepsTheOldShapeAndIsNotAV1Message() throws Exception {
        var lines = List.of(new ReservationService.Line("L-B", LocalDate.parse("2026-12-01"), 30));
        var payload = EventPayloads.stockReserved(UUID.randomUUID(), "SKU-1001", "BOD-UIO", 30, "PEDIDO-1", lines);
        JsonNode v1 = envelopeWrittenBy(DomainEvent.STOCK_RESERVED, UUID.randomUUID().toString(), payload);

        String legacyJson = new LegacyEventAdapter(mapper).toLegacy(DomainEvent.STOCK_RESERVED, "inventory.stock-reserved",
                mapper.writeValueAsString(v1));
        JsonNode legacy = mapper.readTree(legacyJson);
        assertEquals("inventory.stock-reserved", legacy.get("eventType").asText());
        assertEquals(1, legacy.get("schemaVersion").asInt());
        assertTrue(legacy.get("schemaVersion").isInt());
        assertEquals("smart-inventory", legacy.get("producer").asText());
        assertEquals(30, legacy.get("payload").get("quantity").asInt());
        assertEquals("L-B", legacy.get("payload").get("lines").get(0).get("lotCode").asText());
        assertEquals(v1.get("eventId"), legacy.get("eventId"), "ambas copias comparten eventId (dedupe)");
        assertFalse(errorsOf(schema(STOCK_RESERVED_SCHEMA), legacy).isEmpty());
    }
}
