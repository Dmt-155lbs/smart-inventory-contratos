package ec.usfq.smartinventory.messaging;

import ec.usfq.smartinventory.config.RabbitConfig;
import java.util.Arrays;
import java.util.Optional;

/**
 * Catalogo de eventos publicados. Cada entrada fija lo que el contrato (contracts/asyncapi.yaml) exige:
 * eventType, routing key versionada, productor y version semver del esquema. La clave legada solo se publica
 * mientras dure la migracion (smartinventory.events.legacy-routing-keys.enabled).
 */
public enum DomainEvent {

    STOCK_RESERVED("inventory.stock-reserved", RabbitConfig.STOCK_RESERVED, RabbitConfig.LEGACY_STOCK_RESERVED,
            "inventory-service"),
    RISK_DETECTED("replenishment.risk-detected", RabbitConfig.RISK_DETECTED, RabbitConfig.LEGACY_SUGGESTION_OPENED,
            "replenishment-service"),
    /** Evento interno: sin contrato publico ni consumidores (fuera del alcance del Deber 2). */
    SUGGESTION_APPROVED("replenishment.suggestion-approved", RabbitConfig.SUGGESTION_APPROVED, null,
            "replenishment-service");

    /** Version semver publicada del esquema de todos los eventos v1. */
    public static final String SCHEMA_VERSION = "1.0.0";

    private final String eventType;
    private final String routingKey;
    private final String legacyRoutingKey;
    private final String producer;

    DomainEvent(String eventType, String routingKey, String legacyRoutingKey, String producer) {
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.legacyRoutingKey = legacyRoutingKey;
        this.producer = producer;
    }

    public String eventType() { return eventType; }

    public String routingKey() { return routingKey; }

    public Optional<String> legacyRoutingKey() { return Optional.ofNullable(legacyRoutingKey); }

    public String producer() { return producer; }

    public static Optional<DomainEvent> byRoutingKey(String routingKey) {
        return Arrays.stream(values()).filter(e -> e.routingKey.equals(routingKey)).findFirst();
    }
}
