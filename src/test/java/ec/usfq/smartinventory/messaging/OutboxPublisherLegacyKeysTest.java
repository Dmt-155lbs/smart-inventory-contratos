package ec.usfq.smartinventory.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ec.usfq.smartinventory.config.RabbitConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feature flag smartinventory.events.legacy-routing-keys.enabled: con true el publicador envia TAMBIEN con la
 * routing key anterior (coexistencia); con false solo con la del contrato. Sin broker ni BD.
 */
class OutboxPublisherLegacyKeysTest {

    private static final String ENVELOPE = """
            {"eventId":"9b2f6a40-3c1d-4e8a-b5f7-0d4c1a2e7f10","eventType":"inventory.stock-reserved",
             "schemaVersion":"1.0.0","occurredAt":"2026-11-20T14:03:11.250Z",
             "correlationId":"7d0b7f1e-6d5c-4f43-a1d2-0a3f9d6b2c11","producer":"inventory-service",
             "payload":{"reservationId":"3f2a7c1e-8b44-4d1a-9a6e-52d1c0b7e9aa","sku":"SKU-1001","qty":5,
             "lots":[{"lotId":"L-A","qty":5,"expiresAt":"2026-12-15"}],"location":"BOD-UIO","destination":"P-1"}}""";

    private RabbitOperations publishWith(boolean legacyEnabled, String routingKey) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", "9b2f6a40-3c1d-4e8a-b5f7-0d4c1a2e7f10",
                "routing_key", routingKey, "payload", ENVELOPE, "correlation_id", "7d0b7f1e-6d5c-4f43-a1d2-0a3f9d6b2c11")));
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        RabbitOperations ops = mock(RabbitOperations.class);
        when(rabbit.invoke(any(RabbitOperations.OperationsCallback.class))).thenAnswer(inv ->
                ((RabbitOperations.OperationsCallback<?>) inv.getArgument(0)).doInRabbit(ops));
        var tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        when(tx.getTransactionManager().getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var publisher = new OutboxPublisher(jdbc, rabbit, tx, new SimpleMeterRegistry(),
                new LegacyEventAdapter(new ObjectMapper()), legacyEnabled);
        publisher.publishPending();
        return ops;
    }

    @Test
    void flagOnPublishesNewAndLegacyKeys() throws Exception {
        RabbitOperations ops = publishWith(true, RabbitConfig.STOCK_RESERVED);
        verify(ops).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq("inventory.stock-reserved.v1"), any(Object.class), any(MessagePostProcessor.class));
        verify(ops).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq("inventory.stock-reserved"), any(Object.class), any(MessagePostProcessor.class));
        verify(ops).waitForConfirmsOrDie(5_000);
    }

    @Test
    void flagOffPublishesOnlyTheContractKey() throws Exception {
        RabbitOperations ops = publishWith(false, RabbitConfig.STOCK_RESERVED);
        verify(ops, times(1)).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq("inventory.stock-reserved.v1"), any(Object.class), any(MessagePostProcessor.class));
        verify(ops, never()).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), eq("inventory.stock-reserved"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void internalEventsWithoutLegacyKeyArePublishedOnce() throws Exception {
        RabbitOperations ops = publishWith(true, RabbitConfig.SUGGESTION_APPROVED);
        verify(ops, times(1)).convertAndSend(eq(RabbitConfig.EVENTS_EXCHANGE), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }
}
