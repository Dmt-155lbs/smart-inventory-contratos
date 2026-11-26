package ec.usfq.smartinventory.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import ec.usfq.smartinventory.application.ApiException;
import ec.usfq.smartinventory.application.DashboardQueryService;
import ec.usfq.smartinventory.application.InventoryQueryService;
import ec.usfq.smartinventory.application.ReplenishmentService;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.domain.InsufficientStockException;
import ec.usfq.smartinventory.ratelimit.RateLimiter;
import ec.usfq.smartinventory.web.DashboardController;
import ec.usfq.smartinventory.web.InventoryController;
import ec.usfq.smartinventory.web.ProblemWriter;
import ec.usfq.smartinventory.web.ReplenishmentController;
import ec.usfq.smartinventory.web.ReservationController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contrato HTTP: cada peticion y cada respuesta (exito Y error) se validan contra contracts/openapi.yaml con
 * swagger-request-validator. Se ejercita la cadena real de filtros (correlacion, API key + scope, rate limit),
 * los controladores y el manejador de errores; solo los servicios (BD) estan simulados.
 * Si el codigo y el contrato difieren (estado no documentado, campo faltante, cabecera ausente) la prueba falla.
 */
@WebMvcTest({InventoryController.class, ReservationController.class, ReplenishmentController.class,
        DashboardController.class})
@Import({ProblemWriter.class, HttpContractTest.Beans.class})
class HttpContractTest {

    @TestConfiguration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(ec.usfq.smartinventory.config.SecurityProperties.class)
    static class Beans {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final String OPS = "dev-ops-key";               // inventory:read, replenishment:read/approve
    private static final String FULFILLMENT = "dev-fulfillment-key"; // inventory:read, inventory:reserve
    private static final UUID ID = UUID.fromString("0b4f1c52-6a0e-4a3b-8f6d-2f6c9d1a7e10");

    /** Validador estricto: peticion y respuesta deben cumplir el contrato. */
    private static final OpenApiInteractionValidator STRICT =
            OpenApiInteractionValidator.createFor(OpenApiContractTest.SPEC).build();

    /** Para los 401 la peticion no lleva API key (a proposito): se ignora SOLO la seguridad de la peticion. */
    private static final OpenApiInteractionValidator NO_REQUEST_SECURITY =
            OpenApiInteractionValidator.createFor(OpenApiContractTest.SPEC)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.security", ValidationReport.Level.IGNORE).build())
                    .build();

    /** Para el 422 de filtros: la peticion invalida es el punto de la prueba; solo se valida la respuesta. */
    private static final OpenApiInteractionValidator RESPONSE_ONLY =
            OpenApiInteractionValidator.createFor(OpenApiContractTest.SPEC)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE).build())
                    .build();

    @Autowired MockMvc mvc;
    @MockitoBean InventoryQueryService inventory;
    @MockitoBean ReservationService reservations;
    @MockitoBean ReplenishmentService replenishment;
    @MockitoBean DashboardQueryService dashboard;
    @MockitoBean RateLimiter limiter;

    @BeforeEach
    void allowByDefault() {
        when(limiter.tryAcquire(anyString())).thenReturn(new RateLimiter.Decision(true, 20, 19, 0));
    }

    private ResultMatcher valid() {
        return openApi().isValid(STRICT);
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder b, String key) {
        return b.header("X-Api-Key", key);
    }

    private static MockHttpServletRequestBuilder reserve(String key, String idemKey, String body) {
        MockHttpServletRequestBuilder b = post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content(body);
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        return withKey(b, key);
    }

    private static final String BODY =
            "{\"sku\":\"SKU-1001\",\"location\":\"BOD-UIO\",\"quantity\":35,\"destination\":\"PEDIDO-7788\"}";

    private static ReservationService.Result result(boolean replay) {
        return new ReservationService.Result(UUID.fromString("3f2a7c1e-8b44-4d1a-9a6e-52d1c0b7e9aa"), "SKU-1001",
                "BOD-UIO", 35, "PEDIDO-7788", "CONFIRMED",
                List.of(new ReservationService.Line("L-B", LocalDate.parse("2026-12-01"), 30),
                        new ReservationService.Line("L-A", LocalDate.parse("2026-12-15"), 5)), replay);
    }

    private static ReplenishmentService.SuggestionView suggestion(String status) {
        boolean approved = "APPROVED".equals(status);
        return new ReplenishmentService.SuggestionView(ID, "SKU-1001", "PROV-ANDINA", 220, status,
                OffsetDateTime.parse("2026-11-20T14:03:11.250Z"), approved ? "purchasing" : null,
                approved ? OffsetDateTime.parse("2026-11-21T09:15:00.100Z") : null);
    }

    // ------------------------------------------------------------------ GET /api/v1/inventory

    @Test
    void inventory200() throws Exception {
        when(inventory.search(any(), any(), any(), anyInt(), anyInt(), anyString(), anyBoolean())).thenReturn(
                new InventoryQueryService.PageResponse<>(List.of(new InventoryQueryService.StockItem(
                        "SKU-1001", "BOD-UIO", "L-B", LocalDate.parse("2026-12-01"), 30, 0, 30)), 0, 20, 1));
        mvc.perform(withKey(get("/api/v1/inventory?sku=SKU-1001&location=BOD-UIO&size=20&sort=expiresOn&order=asc"), OPS))
                .andExpect(status().isOk()).andExpect(valid())
                .andExpect(header().exists("X-Request-Id")).andExpect(jsonPath("$.items[0].available").value(30));
    }

    @Test
    void inventory400() throws Exception {
        // La peticion (size=1000) incumple el contrato a proposito: se valida la respuesta de error
        mvc.perform(withKey(get("/api/v1/inventory?size=1000"), OPS))
                .andExpect(status().isBadRequest()).andExpect(openApi().isValid(RESPONSE_ONLY))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void inventory401() throws Exception {
        mvc.perform(get("/api/v1/inventory")).andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(NO_REQUEST_SECURITY))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void inventory403() throws Exception {
        mvc.perform(withKey(get("/api/v1/inventory"), "dev-purchasing-key"))   // sin inventory:read
                .andExpect(status().isForbidden()).andExpect(valid()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void inventory429() throws Exception {
        when(limiter.tryAcquire(anyString())).thenReturn(new RateLimiter.Decision(false, 20, 0, 1));
        mvc.perform(withKey(get("/api/v1/inventory"), OPS)).andExpect(status().isTooManyRequests()).andExpect(valid())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void inventory503WhenDatabaseDown() throws Exception {
        when(inventory.search(any(), any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        mvc.perform(withKey(get("/api/v1/inventory"), OPS)).andExpect(status().isServiceUnavailable()).andExpect(valid())
                .andExpect(header().string("Retry-After", "5"));
    }

    // ------------------------------------------------------------------ POST /api/v1/reservations

    @Test
    void reservation201WithLocation() throws Exception {
        when(reservations.reserve(any())).thenReturn(result(false));
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", BODY)).andExpect(status().isCreated()).andExpect(valid())
                .andExpect(header().string("Location", "/api/v1/reservations/3f2a7c1e-8b44-4d1a-9a6e-52d1c0b7e9aa"))
                .andExpect(jsonPath("$.lines[0].lotCode").value("L-B"));
    }

    @Test
    void reservation200IdempotentReplay() throws Exception {
        when(reservations.reserve(any())).thenReturn(result(true));
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", BODY)).andExpect(status().isOk()).andExpect(valid())
                .andExpect(header().string("Idempotent-Replay", "true"));
    }

    @Test
    void reservation400MissingIdempotencyKey() throws Exception {
        mvc.perform(reserve(FULFILLMENT, null, BODY)).andExpect(status().isBadRequest())
                .andExpect(openApi().isValid(RESPONSE_ONLY)).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void reservation400UnreadableBody() throws Exception {
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", "{not json")).andExpect(status().isBadRequest())
                .andExpect(openApi().isValid(RESPONSE_ONLY));
    }

    @Test
    void reservation401() throws Exception {
        mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", "reserva-pedido-7788")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(openApi().isValid(NO_REQUEST_SECURITY));
    }

    @Test
    void reservation403WithoutReserveScope() throws Exception {
        mvc.perform(reserve(OPS, "reserva-pedido-7788", BODY)).andExpect(status().isForbidden()).andExpect(valid())
                .andExpect(jsonPath("$.detail").value("Missing scope inventory:reserve"));
    }

    @Test
    void reservation404NoStockRegistered() throws Exception {
        when(reservations.reserve(any())).thenThrow(ApiException.notFound("No stock registered for sku SKU-1001 at BOD-UIO"));
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", BODY)).andExpect(status().isNotFound()).andExpect(valid());
    }

    @Test
    void reservation409InsufficientStock() throws Exception {
        when(reservations.reserve(any())).thenThrow(new InsufficientStockException(35, 10));
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", BODY)).andExpect(status().isConflict()).andExpect(valid())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void reservation422InvalidBody() throws Exception {
        String bad = "{\"sku\":\"SKU-1001\",\"location\":\"BOD-UIO\",\"quantity\":0,\"destination\":\"X\"}";
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", bad)).andExpect(status().isUnprocessableEntity())
                .andExpect(openApi().isValid(RESPONSE_ONLY)).andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void reservation422IdempotencyKeyReused() throws Exception {
        when(reservations.reserve(any())).thenThrow(new ApiException(422, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with a different request"));
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", BODY)).andExpect(status().isUnprocessableEntity())
                .andExpect(valid()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void reservation429() throws Exception {
        when(limiter.tryAcquire(anyString())).thenReturn(new RateLimiter.Decision(false, 20, 0, 2));
        mvc.perform(reserve(FULFILLMENT, "reserva-pedido-7788", BODY)).andExpect(status().isTooManyRequests())
                .andExpect(valid()).andExpect(header().string("Retry-After", "2"));
    }

    @Test
    void reservationGet200And404() throws Exception {
        UUID id = UUID.fromString("3f2a7c1e-8b44-4d1a-9a6e-52d1c0b7e9aa");
        when(reservations.find(eq(id))).thenReturn(result(false));
        mvc.perform(withKey(get("/api/v1/reservations/" + id), FULFILLMENT)).andExpect(status().isOk()).andExpect(valid());
        UUID missing = UUID.randomUUID();
        when(reservations.find(eq(missing))).thenThrow(ApiException.notFound("Reservation " + missing + " not found"));
        mvc.perform(withKey(get("/api/v1/reservations/" + missing), FULFILLMENT)).andExpect(status().isNotFound()).andExpect(valid());
    }

    // ------------------------------------------------------------------ GET /api/v1/replenishment/suggestions

    @Test
    void suggestions200() throws Exception {
        when(replenishment.list(eq("OPEN"), isNull(), isNull(), anyInt())).thenReturn(List.of(suggestion("OPEN")));
        mvc.perform(withKey(get("/api/v1/replenishment/suggestions?status=OPEN"), OPS)).andExpect(status().isOk())
                .andExpect(valid()).andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void suggestions401() throws Exception {
        mvc.perform(get("/api/v1/replenishment/suggestions")).andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(NO_REQUEST_SECURITY));
    }

    @Test
    void suggestions403() throws Exception {
        mvc.perform(withKey(get("/api/v1/replenishment/suggestions"), FULFILLMENT)).andExpect(status().isForbidden()).andExpect(valid());
    }

    @Test
    void suggestions422InvalidStatusFilter() throws Exception {
        // La peticion ya viola el enum de `status` (ese es el punto): se valida solo la RESPUESTA del contrato
        mvc.perform(withKey(get("/api/v1/replenishment/suggestions?status=BOGUS"), OPS))
                .andExpect(status().isUnprocessableEntity()).andExpect(openApi().isValid(RESPONSE_ONLY))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void suggestions422LimitOutOfRange() throws Exception {
        mvc.perform(withKey(get("/api/v1/replenishment/suggestions?limit=0"), OPS))
                .andExpect(status().isUnprocessableEntity()).andExpect(openApi().isValid(RESPONSE_ONLY))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void suggestions429() throws Exception {
        when(limiter.tryAcquire(anyString())).thenReturn(new RateLimiter.Decision(false, 20, 0, 1));
        mvc.perform(withKey(get("/api/v1/replenishment/suggestions"), OPS)).andExpect(status().isTooManyRequests()).andExpect(valid());
    }

    // ------------------------------------------------------------------ POST /api/v1/replenishment/suggestions/{id}/approve

    private static MockHttpServletRequestBuilder approve(String key, String id) {
        return withKey(post("/api/v1/replenishment/suggestions/" + id + "/approve"), key);
    }

    @Test
    void approve200() throws Exception {
        when(replenishment.approve(eq(ID), anyString(), any())).thenReturn(suggestion("APPROVED"));
        mvc.perform(approve("dev-purchasing-key", ID.toString())).andExpect(status().isOk()).andExpect(valid())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void approve400InvalidId() throws Exception {
        mvc.perform(approve(OPS, "no-es-uuid")).andExpect(status().isBadRequest()).andExpect(openApi().isValid(RESPONSE_ONLY));
    }

    @Test
    void approve401() throws Exception {
        mvc.perform(post("/api/v1/replenishment/suggestions/" + ID + "/approve")).andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(NO_REQUEST_SECURITY));
    }

    @Test
    void approve403WithoutApproveScope() throws Exception {
        mvc.perform(approve(FULFILLMENT, ID.toString())).andExpect(status().isForbidden()).andExpect(valid())
                .andExpect(jsonPath("$.detail").value("Missing scope replenishment:approve"));
    }

    @Test
    void approve404() throws Exception {
        when(replenishment.approve(eq(ID), anyString(), any())).thenThrow(ApiException.notFound("Suggestion " + ID + " not found"));
        mvc.perform(approve(OPS, ID.toString())).andExpect(status().isNotFound()).andExpect(valid());
    }

    @Test
    void approve409InvalidTransition() throws Exception {
        when(replenishment.approve(eq(ID), anyString(), any()))
                .thenThrow(ApiException.conflict("INVALID_TRANSITION", "Suggestion is APPROVED; only OPEN can be approved"));
        mvc.perform(approve(OPS, ID.toString())).andExpect(status().isConflict()).andExpect(valid())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    // ------------------------------------------------------------------ GET /api/v1/dashboard/inventory

    @Test
    void dashboard200() throws Exception {
        when(dashboard.current()).thenReturn(new DashboardQueryService.View(Instant.parse("2026-11-20T14:05:00Z"),
                List.of(new DashboardQueryService.Row("SKU-1001", 140, 3, UUID.randomUUID(), "CONFIRMED",
                        Instant.parse("2026-11-20T14:03:11Z"), Instant.parse("2026-11-20T14:03:12Z"), 1000, false))));
        mvc.perform(withKey(get("/api/v1/dashboard/inventory"), OPS)).andExpect(status().isOk()).andExpect(valid());
    }
}
