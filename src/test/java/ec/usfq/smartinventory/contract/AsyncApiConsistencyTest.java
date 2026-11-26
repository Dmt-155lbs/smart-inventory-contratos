package ec.usfq.smartinventory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ec.usfq.smartinventory.config.RabbitConfig;
import ec.usfq.smartinventory.messaging.DomainEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.yaml.snakeyaml.Yaml;

/**
 * contracts/asyncapi.yaml debe describir la topologia REAL (RabbitConfig, application.yml, DomainEvent).
 * Si alguien cambia una routing key, una cola, el exchange o la politica de reintentos sin actualizar el contrato,
 * esta prueba falla.
 */
@SuppressWarnings("unchecked")
class AsyncApiConsistencyTest {

    private static Map<String, Object> yaml(String path) throws IOException {
        try (var in = Files.newBufferedReader(Path.of(path))) {
            return new Yaml().load(in);
        }
    }

    private static Map<String, Object> channels() throws IOException {
        return (Map<String, Object>) yaml("contracts/asyncapi.yaml").get("channels");
    }

    private static Set<String> addresses() throws IOException {
        return channels().values().stream().map(c -> (String) ((Map<String, Object>) c).get("address"))
                .collect(Collectors.toSet());
    }

    @Test
    void isAsyncApi3WithBothEvents() throws IOException {
        var doc = yaml("contracts/asyncapi.yaml");
        assertTrue(doc.get("asyncapi").toString().startsWith("3.0"));
        var messages = ((Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("messages")).keySet();
        assertTrue(messages.containsAll(List.of("StockReserved", "RiskDetected")));
    }

    @Test
    void routingKeysAndQueuesMatchTheCode() throws IOException {
        Set<String> addr = addresses();
        for (DomainEvent e : List.of(DomainEvent.STOCK_RESERVED, DomainEvent.RISK_DETECTED)) {
            assertTrue(addr.contains(e.routingKey()), "asyncapi sin la routing key " + e.routingKey());
            assertTrue(addr.contains(e.legacyRoutingKey().orElseThrow()), "asyncapi sin la clave legada de " + e);
        }
        assertEquals("inventory.stock-reserved.v1", DomainEvent.STOCK_RESERVED.routingKey());
        assertEquals("replenishment.risk-detected.v1", DomainEvent.RISK_DETECTED.routingKey());
        assertTrue(addr.containsAll(List.of(RabbitConfig.DASHBOARD_Q, RabbitConfig.REPLENISHMENT_Q,
                RabbitConfig.PURCHASING_RISK_Q, RabbitConfig.DLQ)));
    }

    @Test
    void exchangeIsDurableTopicNamedLikeTheCode() throws IOException {
        for (String ch : List.of("stockReserved", "riskDetected")) {
            var amqp = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) channels().get(ch))
                    .get("bindings")).get("amqp");
            var ex = (Map<String, Object>) amqp.get("exchange");
            assertEquals(RabbitConfig.EVENTS_EXCHANGE, ex.get("name"));
            assertEquals("topic", ex.get("type"));
            assertEquals(true, ex.get("durable"));
        }
        var rc = new RabbitConfig();
        assertEquals(RabbitConfig.EVENTS_EXCHANGE, rc.eventsExchange().getName());
        assertTrue(rc.eventsExchange().isDurable());
    }

    @Test
    void queueBindingsMatchTheDeclaredTopology() {
        var rc = new RabbitConfig();
        assertEquals(RabbitConfig.STOCK_RESERVED, rc.dashboardBinding().getRoutingKey());
        assertEquals(RabbitConfig.STOCK_RESERVED, rc.replenishmentBinding().getRoutingKey());
        assertEquals(RabbitConfig.RISK_DETECTED, rc.purchasingRiskBinding().getRoutingKey());
        assertEquals(RabbitConfig.LEGACY_STOCK_RESERVED, rc.dashboardLegacyBinding().getRoutingKey());
        assertEquals(RabbitConfig.LEGACY_SUGGESTION_OPENED, rc.purchasingRiskLegacyBinding().getRoutingKey());
        for (Binding b : List.of(rc.dashboardBinding(), rc.replenishmentBinding(), rc.purchasingRiskBinding())) {
            assertEquals(RabbitConfig.EVENTS_EXCHANGE, b.getExchange());
        }
        for (Queue q : List.of(rc.dashboardQueue(), rc.replenishmentQueue(), rc.purchasingRiskQueue())) {
            assertTrue(q.isDurable());
            assertEquals(RabbitConfig.DLX, q.getArguments().get("x-dead-letter-exchange"), q.getName() + " sin DLX");
            assertEquals("dead", q.getArguments().get("x-dead-letter-routing-key"));
        }
        assertEquals("dead", rc.dlqBinding().getRoutingKey());
        assertEquals(RabbitConfig.DLX, rc.dlqBinding().getExchange());
    }

    @Test
    void retryPolicyInContractMatchesApplicationYml() throws IOException {
        var app = yaml("src/main/resources/application.yml");
        var retry = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) app.get("spring")).get("rabbitmq")).get("listener")).get("simple")).get("retry");
        var ops = (Map<String, Object>) yaml("contracts/asyncapi.yaml").get("operations");
        var policy = (Map<String, Object>) ((Map<String, Object>) ops.get("consumeStockReservedDashboard"))
                .get("x-consumer-policy");
        var contractRetry = (Map<String, Object>) policy.get("retry");
        assertEquals(retry.get("max-attempts"), contractRetry.get("maxAttempts"));
        assertEquals(retry.get("initial-interval"), contractRetry.get("initialInterval"));
        assertEquals(retry.get("max-interval"), contractRetry.get("maxInterval"));
        assertEquals(((Number) retry.get("multiplier")).doubleValue(), ((Number) contractRetry.get("multiplier")).doubleValue());
        var simple = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) app.get("spring")).get("rabbitmq")).get("listener")).get("simple");
        assertEquals(false, simple.get("default-requeue-rejected"), "tras agotar reintentos: sin requeue => DLQ");
        assertEquals("correlated", ((Map<String, Object>) ((Map<String, Object>) app.get("spring")).get("rabbitmq"))
                .get("publisher-confirm-type"));
    }

    @Test
    void messagePayloadsReferenceTheSchemaFilesThatExist() throws IOException {
        String text = Files.readString(Path.of("contracts/asyncapi.yaml"));
        for (String f : List.of("stock-reserved.v1.schema.json", "replenishment-risk-detected.v1.schema.json")) {
            assertTrue(text.contains("./schemas/" + f), "asyncapi no referencia " + f);
            assertTrue(Files.exists(Path.of("contracts/schemas", f)), "no existe " + f);
        }
    }
}
