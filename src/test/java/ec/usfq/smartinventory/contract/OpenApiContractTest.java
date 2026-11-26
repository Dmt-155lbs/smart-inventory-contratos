package ec.usfq.smartinventory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contrato primero: contracts/openapi.yaml debe ser valido y cumplir la politica del deber (codigos de error
 * documentados, errores en problem+json, 429 con Retry-After, scope por operacion, ejemplos con nombre).
 * Corre sin levantar Spring ni BD. El comportamiento real lo verifica HttpContractTest.
 */
class OpenApiContractTest {

    static final String SPEC = "contracts/openapi.yaml";

    private static OpenAPI load() {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        SwaggerParseResult r = new OpenAPIV3Parser().readLocation(SPEC, null, opts);
        assertTrue(r.getMessages() == null || r.getMessages().isEmpty(), "OpenAPI invalido: " + r.getMessages());
        return r.getOpenAPI();
    }

    private static List<Operation> operations(OpenAPI api) {
        return api.getPaths().values().stream().flatMap(p -> p.readOperations().stream()).toList();
    }

    private static Operation op(OpenAPI api, String path, PathItem.HttpMethod method) {
        PathItem item = api.getPaths().get(path);
        assertNotNull(item, "falta " + path);
        Operation o = item.readOperationsMap().get(method);
        assertNotNull(o, "falta " + method + " " + path);
        return o;
    }

    private static void assertResponses(Operation o, String... codes) {
        for (String code : codes) {
            assertTrue(o.getResponses().containsKey(code), o.getOperationId() + " no documenta " + code);
        }
    }

    @Test
    void documentsAllRequiredEndpoints() {
        OpenAPI api = load();
        for (String p : List.of("/api/v1/inventory", "/api/v1/reservations", "/api/v1/replenishment/suggestions",
                "/api/v1/replenishment/suggestions/{id}/approve", "/api/v1/dashboard/inventory")) {
            assertNotNull(api.getPaths().get(p), "falta " + p);
        }
    }

    @Test
    void documentVersionIsNotTheApiVersion() {
        OpenAPI api = load();
        assertTrue(api.getInfo().getVersion().matches("\\d+\\.\\d+\\.\\d+"), "info.version debe ser semver");
        String d = api.getInfo().getDescription();
        assertTrue(d.contains("version del documento") || d.contains("Version del documento"), "debe explicar doc != API");
        assertTrue(d.contains("/api/v1"), "debe nombrar la version de la API (/api/v1)");
        api.getPaths().keySet().forEach(p -> assertTrue(p.startsWith("/api/v1/"), p + " fuera de /api/v1"));
    }

    @Test
    void requiredOperationsDocumentTheResponsesOfTheGuide() {
        OpenAPI api = load();
        assertResponses(op(api, "/api/v1/inventory", PathItem.HttpMethod.GET), "200", "400", "401", "403");
        assertResponses(op(api, "/api/v1/reservations", PathItem.HttpMethod.POST), "201", "400", "401", "403", "409", "422", "429");
        assertResponses(op(api, "/api/v1/replenishment/suggestions", PathItem.HttpMethod.GET), "200", "401", "403", "422");
        assertResponses(op(api, "/api/v1/replenishment/suggestions/{id}/approve", PathItem.HttpMethod.POST),
                "200", "401", "403", "404", "409");
    }

    @Test
    void everyOperationDocumentsAuthRateLimitSecurityAndScope() {
        for (Operation op : operations(load())) {
            for (String code : List.of("401", "403", "429")) {
                assertTrue(op.getResponses().containsKey(code), op.getOperationId() + " no documenta " + code);
            }
            assertNotNull(op.getExtensions().get("x-required-scope"), op.getOperationId() + " sin scope");
            assertTrue(op.getSecurity() != null && !op.getSecurity().isEmpty(), op.getOperationId() + " sin security");
            assertNotNull(op.getSummary(), op.getOperationId() + " sin summary");
        }
    }

    @Test
    void scopesAreTheOnesTheCodeEnforces() {
        OpenAPI api = load();
        assertEquals("inventory:read", op(api, "/api/v1/inventory", PathItem.HttpMethod.GET).getExtensions().get("x-required-scope"));
        assertEquals("inventory:reserve", op(api, "/api/v1/reservations", PathItem.HttpMethod.POST).getExtensions().get("x-required-scope"));
        assertEquals("replenishment:read", op(api, "/api/v1/replenishment/suggestions", PathItem.HttpMethod.GET).getExtensions().get("x-required-scope"));
        assertEquals("replenishment:approve",
                op(api, "/api/v1/replenishment/suggestions/{id}/approve", PathItem.HttpMethod.POST).getExtensions().get("x-required-scope"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scopes = (Map<String, Object>) api.getComponents().getSecuritySchemes().get("ApiKey")
                .getExtensions().get("x-scopes");
        assertTrue(scopes.keySet().containsAll(List.of("inventory:read", "inventory:reserve", "replenishment:read", "replenishment:approve")));
    }

    @Test
    void everyErrorResponseIsProblemJsonOnly() {
        for (Operation op : operations(load())) {
            op.getResponses().forEach((code, resp) -> {
                if (code.startsWith("4") || code.startsWith("5")) {
                    assertNotNull(resp.getContent(), op.getOperationId() + " " + code + " sin contenido");
                    assertEquals(List.of("application/problem+json"), List.copyOf(resp.getContent().keySet()),
                            op.getOperationId() + " " + code + " debe ser solo application/problem+json");
                }
            });
        }
    }

    @Test
    void problemSchemaFollowsRfc9457WithExtensions() {
        var problem = load().getComponents().getSchemas().get("Problem");
        for (String p : List.of("type", "title", "status", "detail", "instance", "code", "correlationId", "errors")) {
            assertTrue(problem.getProperties().containsKey(p), "Problem sin " + p);
        }
        assertTrue(problem.getRequired().containsAll(List.of("type", "title", "status", "detail", "instance")));
    }

    @Test
    void reservationDocumentsIdempotencyLocationAndConflictCodes() {
        Operation post = load().getPaths().get("/api/v1/reservations").getPost();
        for (String code : List.of("200", "201", "400", "404", "409", "422")) {
            assertTrue(post.getResponses().containsKey(code), "reservations no documenta " + code);
        }
        assertTrue(post.getParameters().stream().anyMatch(x -> "Idempotency-Key".equals(x.getName())
                && "header".equals(x.getIn()) && Boolean.TRUE.equals(x.getRequired())));
        assertTrue(post.getResponses().get("201").getHeaders().containsKey("Location"), "201 sin Location");
    }

    @Test
    void rateLimitResponseDeclaresRetryAfter() {
        var tooMany = load().getComponents().getResponses().get("TooManyRequests");
        assertTrue(tooMany.getHeaders().containsKey("Retry-After"));
        assertTrue(Boolean.TRUE.equals(tooMany.getHeaders().get("Retry-After").getRequired()));
    }

    @Test
    void everyOperationHasNamedResponseExamples() {
        // Cada operacion publica al menos un ejemplo con nombre en alguna respuesta (2xx o error)
        OpenAPI api = load();
        for (String[] t : new String[][]{{"/api/v1/inventory", "GET"}, {"/api/v1/reservations", "POST"},
                {"/api/v1/replenishment/suggestions", "GET"}, {"/api/v1/replenishment/suggestions/{id}/approve", "POST"}}) {
            Operation o = op(api, t[0], PathItem.HttpMethod.valueOf(t[1]));
            boolean named = o.getResponses().values().stream().map(ApiResponse::getContent)
                    .filter(c -> c != null)
                    .flatMap(c -> c.values().stream())
                    .anyMatch(mt -> mt.getExamples() != null && !mt.getExamples().isEmpty());
            assertTrue(named, o.getOperationId() + " sin ejemplos con nombre");
        }
    }
}
