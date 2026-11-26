# Smart Inventory · solución de referencia (Deber 2, Desarrollo de Software Moderno)

Recorrido vertical: **consultar stock → reservar con FEFO (idempotente) → evento por Outbox → dashboard y reposición por consumidores idempotentes**, con CI/CD DevSecOps y observabilidad.

## Ejecutar

```bash
docker compose up -d --build          # postgres, rabbitmq, app, prometheus, grafana
bash scripts/sat.sh                   # SAT automatizado (docs/sat.md)
k6 run perf/reserve.js                # carga controlada (p95 objetivo: consulta < 200 ms, reserva < 400 ms)
mvn verify                            # unitarias + contrato + integracion (Testcontainers, requiere Docker)
```

Swagger UI: `http://localhost:8080/swagger-ui.html` · Prometheus `:9090` · RabbitMQ `:15672` (guest/guest) · Grafana `:3000`.
Claves de **desarrollo** (`X-Api-Key`): `dev-ops-key`, `dev-fulfillment-key`, `dev-purchasing-key`. En staging se inyectan por variables de entorno del gestor de secretos; nunca se versionan.

```bash
curl -s -H 'X-Api-Key: dev-fulfillment-key' -H 'Idempotency-Key: demo-0001' -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1001","location":"BOD-UIO","quantity":35,"destination":"PEDIDO-1"}' localhost:8080/api/v1/reservations
```

## Mapa historia → implementación → prueba

| Historia | Endpoint / evento | Código | Prueba |
|---|---|---|---|
| US-01 | `GET /api/v1/inventory` | `InventoryController`, `InventoryQueryService` | SAT-1, contrato |
| US-02 | `POST /api/v1/reservations` | `ReservationService`, `FefoAllocationPolicy` | `FefoAllocationPolicyTest`, `ReservationFlowIT`, SAT-3..5 |
| US-03 | `GET/POST …/replenishment/suggestions` | `ReplenishmentConsumer`, `ReorderPointRule`, `ReplenishmentService` | `ReorderPointRuleTest`, SAT-7/8 |
| US-04 | `GET /api/v1/dashboard/inventory` | `DashboardConsumer`, `DashboardQueryService` | SAT-6, SAT-9 |

## Arquitectura (resumen)

`web` (filtros: correlación → autenticación/scope → rate limit; controladores; `problem+json`) → `application` (casos de uso transaccionales) → `domain` (FEFO, reposición: Java puro, sin Spring) · `ratelimit` (token bucket / ventana fija) · `messaging` (Outbox, publicador, consumidores, idempotencia).
Decisiones en `docs/adr/`. Contratos (HTTP y eventos) en `contracts/` (ver la seccion *Contratos*). Runbook en `docs/runbook.md`.

## Patrones y SOLID (dónde está cada uno)

| Patrón | Ubicación | Beneficio probado |
|---|---|---|
| Strategy | `AllocationPolicy`, `ReplenishmentRule`, `RateLimiter` | Prueba unitaria de FEFO sin BD; comparación de estrategias de rate limit |
| Ports & Adapters | `domain`/`application` no importan broker; `messaging` y JDBC son adaptadores | Dominio probado sin infraestructura |
| Outbox + consumidor idempotente | `OutboxWriter`, `OutboxPublisher`, `ProcessedEvents` | `ReservationFlowIT` (atomicidad), SAT-9 |
| Unit of Work | `@Transactional` en el caso de uso | Rollback completo ante `InsufficientStockException` |

Se omitió *Repository* como capa separada a propósito: con SQL explícito y 4 consultas, una capa extra sería un patrón "para cumplir la lista" (ver ADR). Los equipos pueden extraerlo si añaden más agregados.

## Qué se verificó aquí y qué no

| Parte | Estado |
|---|---|
| Dominio (FEFO, reposición) y rate limiters: 15 pruebas | **Ejecutadas y aprobadas** (compiladas con `javac`, sin Maven) |
| `contracts/openapi.yaml`, `contracts/asyncapi.yaml`, JSON Schemas | Validados con redocly, asyncapi, ajv y las pruebas de contrato (`mvn verify`); ver *Contratos* |
| Capa Spring (filtros, servicios, outbox, consumidores), `ReservationFlowIT`, `OpenApiContractTest`, Dockerfile, compose, workflow | **Escritos pero NO compilados ni ejecutados**: el entorno de generación no alcanzaba Maven Central ni tenía broker/BD. Correr `mvn verify` primero y corregir lo que aparezca |

## DAST autenticado
El baseline de ZAP apunta a `/v3/api-docs` (público). Para escanear endpoints protegidos, crear un *context* de ZAP con la cabecera `X-Api-Key` de la cuenta de prueba de staging y registrar hallazgos (severidad, evidencia no sensible, decisión, responsable) en `docs/security-findings.md`.

## Límites conocidos (para discutir en clase)
- Rate limit en memoria por instancia; no limita peticiones sin credenciales (401 antes del límite).
- Autenticación por API key con scopes: suficiente para el laboratorio; producción usaría OIDC/JWT.
- `outbox` y `idempotency_key` crecen: falta job de purga.
- Datos semilla con fechas de nov-2026.

## Contratos

El contrato va **primero** y se valida automaticamente en cada PR (`.github/workflows/contracts.yml`).

```
contracts/
  openapi.yaml                       API HTTP v1 (OpenAPI 3.0.3): operaciones, scopes, schemas, ejemplos, errores problem+json
  asyncapi.yaml                      Eventos (AsyncAPI 3.0): exchange, routing keys, colas, bindings, ack/retry/DLQ
  schemas/
    stock-reserved.v1.schema.json              inventory.stock-reserved.v1 (sobre + payload, JSON Schema 2020-12)
    replenishment-risk-detected.v1.schema.json replenishment.risk-detected.v1
  examples/                          ejemplos validos (+ invalid/ : deben ser rechazados por el schema)
  COMPATIBILITY.md                   reglas de versionado, migracion y reporte de compatibilidad generado
redocly.yaml                         reglas de lint de OpenAPI
scripts/validate-contracts.sh        lint/validacion local (redocly, asyncapi, ajv)
scripts/compat-report.sh             compatibilidad vs rama base (oasdiff + scripts/schema-compat.mjs)
```

> La guia del deber nombra el primer schema `reservation-created.v1.schema.json`; aqui se llama `stock-reserved.v1.schema.json`
> por coherencia con el evento (`inventory.stock-reserved`) y su routing key. `info.version` (1.0.0) versiona el *documento*;
> la version de la API es `/api/v1` y la del evento `.v1` + `schemaVersion` semver (ver `contracts/COMPATIBILITY.md`).

**Validar en local** (requiere Node 20+, Docker para oasdiff y Docker/Maven para las pruebas Java):

```bash
bash scripts/validate-contracts.sh            # redocly lint + asyncapi validate + ajv (schemas y ejemplos validos/invalidos)
mvn test -Dtest='OpenApiContractTest,HttpContractTest,EventContractTest,AsyncApiConsistencyTest'   # contrato HTTP y de eventos
mvn verify                                    # todo, incluidas las pruebas de integracion (Testcontainers)
bash scripts/compat-report.sh main            # cambios incompatibles respecto a main (exit 1 si los hay); --update-doc actualiza COMPATIBILITY.md
```

**Migracion de routing keys.** Los eventos ahora se publican como `inventory.stock-reserved.v1` y `replenishment.risk-detected.v1`.
Mientras `smartinventory.events.legacy-routing-keys.enabled=true` (variable `LEGACY_ROUTING_KEYS_ENABLED`, por defecto `true`)
tambien se publican con las claves anteriores y sobre anterior, y las colas siguen enlazadas a ambas. Plan de retiro en `contracts/COMPATIBILITY.md`.

Cambios de comportamiento introducidos por el contrato: los errores `problem+json` llevan `instance` y `correlationId` (antes
`requestId`); `X-Request-Id` solo se acepta si es UUID; `POST /reservations` devuelve `Location` y existe
`GET /api/v1/reservations/{id}`; los filtros invalidos de `GET /replenishment/suggestions` responden 422.
