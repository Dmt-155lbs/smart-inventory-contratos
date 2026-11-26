package ec.usfq.smartinventory.config;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia (documentada en contracts/asyncapi.yaml): exchange topic durable -> colas por consumidor -> DLX -> DLQ.
 * Un mensaje rechazado tras agotar reintentos (spring.rabbitmq.listener.simple.retry: 3 intentos, backoff 1s x2,
 * maximo 10s) va a inventory.dlq.
 *
 * Migracion de routing keys: mientras smartinventory.events.legacy-routing-keys.enabled sea true (valor por
 * defecto), cada cola tambien se enlaza a la clave anterior y OutboxPublisher publica ambas copias.
 */
@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "smartinventory.events";
    public static final String DLX = "inventory.dlx";
    public static final String DLQ = "inventory.dlq";
    public static final String DASHBOARD_Q = "dashboard.q";
    public static final String REPLENISHMENT_Q = "replenishment.q";
    public static final String PURCHASING_RISK_Q = "purchasing.risk.q";

    // Routing keys del contrato (v1)
    public static final String STOCK_RESERVED = "inventory.stock-reserved.v1";
    public static final String RISK_DETECTED = "replenishment.risk-detected.v1";
    // Evento interno, sin contrato publico
    public static final String SUGGESTION_APPROVED = "replenishment.suggestion-approved";
    // Claves anteriores (deprecadas; se retiran al terminar la migracion)
    public static final String LEGACY_STOCK_RESERVED = "inventory.stock-reserved";
    public static final String LEGACY_SUGGESTION_OPENED = "replenishment.suggestion-opened";

    public static final String LEGACY_FLAG = "smartinventory.events.legacy-routing-keys.enabled";

    private static final long DLQ_RETENTION_MS = 7L * 24 * 3600 * 1000;
    private static final int PURCHASING_TTL_MS = 24 * 3600 * 1000;
    private static final int PURCHASING_MAX_LENGTH = 10_000;

    @Bean public TopicExchange eventsExchange() { return new TopicExchange(EVENTS_EXCHANGE, true, false); }

    @Bean public DirectExchange deadLetterExchange() { return new DirectExchange(DLX, true, false); }

    @Bean public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).withArguments(Map.of("x-message-ttl", DLQ_RETENTION_MS)).build();
    }

    @Bean public Queue dashboardQueue() {
        return QueueBuilder.durable(DASHBOARD_Q).deadLetterExchange(DLX).deadLetterRoutingKey("dead").build();
    }

    @Bean public Queue replenishmentQueue() {
        return QueueBuilder.durable(REPLENISHMENT_Q).deadLetterExchange(DLX).deadLetterRoutingKey("dead").build();
    }

    /** Cola para el consumidor externo (compras). Acotada: no crece sin limite si nadie la consume. */
    @Bean public Queue purchasingRiskQueue() {
        return QueueBuilder.durable(PURCHASING_RISK_Q)
                .ttl(PURCHASING_TTL_MS).maxLength(PURCHASING_MAX_LENGTH).overflow(QueueBuilder.Overflow.dropHead)
                .deadLetterExchange(DLX).deadLetterRoutingKey("dead").build();
    }

    @Bean public Binding dlqBinding() { return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with("dead"); }

    // --- Enlaces v1 (cada consumidor tiene su propia cola: uno lento o caido no afecta al otro) ---

    @Bean public Binding dashboardBinding() {
        return BindingBuilder.bind(dashboardQueue()).to(eventsExchange()).with(STOCK_RESERVED);
    }

    @Bean public Binding replenishmentBinding() {
        return BindingBuilder.bind(replenishmentQueue()).to(eventsExchange()).with(STOCK_RESERVED);
    }

    @Bean public Binding purchasingRiskBinding() {
        return BindingBuilder.bind(purchasingRiskQueue()).to(eventsExchange()).with(RISK_DETECTED);
    }

    // --- Enlaces legados (solo durante la migracion) ---

    @Bean
    @ConditionalOnProperty(name = LEGACY_FLAG, havingValue = "true", matchIfMissing = true)
    public Binding dashboardLegacyBinding() {
        return BindingBuilder.bind(dashboardQueue()).to(eventsExchange()).with(LEGACY_STOCK_RESERVED);
    }

    @Bean
    @ConditionalOnProperty(name = LEGACY_FLAG, havingValue = "true", matchIfMissing = true)
    public Binding replenishmentLegacyBinding() {
        return BindingBuilder.bind(replenishmentQueue()).to(eventsExchange()).with(LEGACY_STOCK_RESERVED);
    }

    @Bean
    @ConditionalOnProperty(name = LEGACY_FLAG, havingValue = "true", matchIfMissing = true)
    public Binding purchasingRiskLegacyBinding() {
        return BindingBuilder.bind(purchasingRiskQueue()).to(eventsExchange()).with(LEGACY_SUGGESTION_OPENED);
    }
}
