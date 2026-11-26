# Compatibilidad y versionado de contratos

Este documento fija las reglas para evolucionar `contracts/openapi.yaml`, `contracts/asyncapi.yaml` y
`contracts/schemas/*.schema.json` sin romper a los consumidores, y contiene (al final) el **reporte de compatibilidad
generado** por `scripts/compat-report.sh`.

> **Nota de nombres.** La guia del deber llama al primer schema `reservation-created.v1.schema.json`. Aqui se llama
> `stock-reserved.v1.schema.json` para ser coherente con el evento (`inventory.stock-reserved`), la routing key
> (`inventory.stock-reserved.v1`) y el resto del codigo. El segundo schema es `replenishment-risk-detected.v1.schema.json`.

## 1. Tres versiones distintas (no confundir)

| Que se versiona | Donde vive | Ejemplo | Cuando cambia |
|---|---|---|---|
| **Documento** OpenAPI / AsyncAPI | `info.version` | `1.0.0` | Cada edicion del documento (texto, ejemplos, campos opcionales). |
| **API HTTP** | prefijo de la URL | `/api/v1` | Solo ante un cambio incompatible: nace `/api/v2`. |
| **Evento** | routing key `.v1`, `eventType`, `schemaVersion` (semver) | `inventory.stock-reserved.v1`, `1.0.0` | Menor (`1.1.0`) ante un campo opcional; nueva version mayor (`.v2`) ante un cambio incompatible. |

Un documento OpenAPI `1.7.0` puede describir perfectamente la API `v1`. Subir la version del documento **no** crea una API nueva.

## 2. Reglas

### Compatible: se queda en v1
- **Agregar un campo opcional** a una respuesta, a un payload de evento o a un parametro opcional.
  Evento: `schemaVersion` pasa a `1.1.0`, la routing key sigue siendo `.v1`.
- Agregar un endpoint, un codigo de respuesta nuevo documentado, un valor nuevo en un campo de salida abierto.
- Aclarar descripciones o ejemplos (solo sube la version del documento).
- **Los consumidores deben ignorar los campos desconocidos** (por eso el `payload` de los schemas admite
  `additionalProperties`; el sobre, en cambio, es estricto: modificarlo es un cambio de plataforma).

### Incompatible: exige v2
- Eliminar o **renombrar** un campo, cambiar su tipo, formato o **significado**, volver obligatorio un campo opcional,
  quitar un valor de enum, endurecer una validacion de entrada, quitar un endpoint o un codigo de respuesta.
- Regla de oro: **nunca se reutiliza una version publicada con otro significado**. `v1` queda congelada.

### Procedimiento de migracion a v2 (convivencia)
1. Publicar el contrato nuevo **al lado** del anterior: `/api/v2/...` y/o `inventory.stock-reserved.v2` (routing key, `eventType`
   y archivo `*.v2.schema.json` nuevos). v1 no se toca.
2. **Doble publicacion**: el productor emite v1 y v2 durante la ventana de convivencia (como hoy con las claves legadas, via
   feature flag); las colas de los consumidores se enlazan a ambas claves y deduplican por `eventId`.
3. Anunciar la **fecha de deprecacion** de v1 (cabecera `Deprecation`/`Sunset` en HTTP; `x-deprecated` y fecha en AsyncAPI).
4. Migrar consumidores uno a uno; medir trafico residual en v1.
5. Pasada la fecha y con trafico v1 en cero: apagar el flag, quitar bindings y el codigo v1, y archivar el schema v1 como historico.

## 3. Ejemplos concretos

**Compatible - campo opcional.** Se agrega `warehouseZone` opcional a `lots[]` de `inventory.stock-reserved`:
`schemaVersion` `1.1.0`, misma routing key `.v1`. Un consumidor v1 sigue validando el mensaje
(`contracts/examples/stock-reserved.v1.1.compatible.json` lo demuestra y `EventContractTest` lo verifica).
`schema-compat.mjs` lo clasifica como `COMPATIBLE: propiedad opcional nueva`.

**Incompatible - renombrar.** Renombrar `qty` a `quantity` en el payload rompe a todo consumidor que lee `payload.qty`.
No se hace sobre v1: se crea `inventory.stock-reserved.v2` con `quantity`, se publica en paralelo y se deprecia v1.
Si alguien lo hace en v1, el schema compat reporta `BREAKING: required quitado: qty` y
`BREAKING: propiedad quitada o renombrada`, `EventContractTest` falla y el PR queda bloqueado (ver rama `demo/cambio-incompatible`).

**Incompatible - HTTP.** Quitar `lines` de la respuesta de `POST /reservations` o volver obligatorio un parametro:
`oasdiff breaking` lo marca `ERR` (`response-required-property-removed`, `new-required-request-parameter`...) y el job
`compatibility` falla.

## 4. Migracion vigente de routing keys

| Antes (deprecada) | Ahora (contrato) | Estado |
|---|---|---|
| `inventory.stock-reserved` | `inventory.stock-reserved.v1` | Se publican **ambas** mientras `smartinventory.events.legacy-routing-keys.enabled=true` (por defecto). |
| `replenishment.suggestion-opened` | `replenishment.risk-detected.v1` | Idem. |
| `replenishment.suggestion-approved` | (sin cambio) | Evento interno, sin consumidores ni contrato publico. |

- La copia legada conserva el **sobre anterior** (`schemaVersion` entero `1`, `producer: smart-inventory`, payload `quantity`/`lines`)
  para no romper consumidores externos que aun no migraron (`LegacyEventAdapter`).
- Las colas propias (`dashboard.q`, `replenishment.q`, `purchasing.risk.q`) estan enlazadas a la clave nueva **y** a la legada;
  como ambas copias comparten `eventId`, el consumidor idempotente (`processed_event`) procesa una sola vez.
- **Plan de retiro**: (1) T0 = despliegue de esta version, anunciar a los consumidores externos; (2) T0 + 4 semanas: confirmar
  en RabbitMQ que no hay consumidores en las claves legadas; (3) T0 + 6 semanas: `LEGACY_ROUTING_KEYS_ENABLED=false`;
  (4) siguiente release: borrar `LegacyEventAdapter`, las constantes `LEGACY_*` y los bindings legados, y borrar a mano los bindings
  huerfanos del broker. Las fechas se ajustan al acuerdo con cada consumidor.

## 5. Como se verifica automaticamente

| Control | Herramienta | Falla cuando |
|---|---|---|
| Sintaxis y estilo OpenAPI | `redocly lint` | contrato invalido o sin `operationId`, ejemplos que no validan, etc. |
| Sintaxis AsyncAPI | `asyncapi validate` | documento invalido |
| Schemas y ejemplos | `ajv` (draft 2020-12) | schema no compila, ejemplo valido no valida, ejemplo **invalido** valida |
| API real vs contrato | `HttpContractTest` (swagger-request-validator) | peticion/respuesta (exito y error) difiere del YAML |
| Eventos reales vs schema | `EventContractTest` (networknt) | el sobre de `OutboxWriter` no cumple el schema |
| Topologia real vs AsyncAPI | `AsyncApiConsistencyTest` | routing keys, colas, exchange o reintentos distintos del codigo |
| Compatibilidad | `oasdiff breaking` + `schema-compat.mjs` | cambio incompatible en v1 respecto a la rama base |

Local: `bash scripts/compat-report.sh main` (ver README, seccion Contratos).

## 6. Reporte generado

<!-- compat-report:start -->
## Reporte de compatibilidad de contratos

- Base: `main` (16b8c28)  -  Cambio propuesto: `3b99cd3` + arbol de trabajo
- Generado: 2026-11-26T00:33:31Z por `scripts/compat-report.sh`

### OpenAPI (oasdiff) - contracts/openapi.yaml

La base `main` no contiene `contracts/openapi.yaml`: es la **primera publicacion** del contrato, no hay linea base
contra la cual comparar (resultado: sin cambios incompatibles).

**Informativo (no bloquea)** - diferencias respecto al `openapi/openapi.yaml` original del curso:

```
(30 hallazgos; se muestran los primeros 12 - son esperados: el contrato original no estaba publicado)
29 changes: 29 error, 0 warning, 0 info
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/dashboard/inventory removed the required property `requestId` from the response with the `401` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/dashboard/inventory removed the required property `requestId` from the response with the `403` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/dashboard/inventory removed the required property `requestId` from the response with the `429` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/inventory removed the required property `requestId` from the response with the `400` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/inventory removed the required property `requestId` from the response with the `401` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/inventory removed the required property `requestId` from the response with the `403` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/inventory removed the required property `requestId` from the response with the `429` status [response-required-property-removed]. 
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/inventory removed the required property `requestId` from the response with the `503` status [response-required-property-removed]. 
error at contracts/openapi.yaml, in API GET /api/v1/replenishment/suggestions added the pattern `^[A-Za-z0-9_-]{1,64}$` to the `query` request parameter `sku` [request-parameter-pattern-added]. This is a breaking change because adding a pattern restriction to a previously unrestricted parameter will reject values that were previously accepted, breaking existing clients
error at contracts/openapi.yaml, in API GET /api/v1/replenishment/suggestions added the pattern `^[A-Za-z0-9_-]{1,64}$` to the `query` request parameter `supplier` [request-parameter-pattern-added]. This is a breaking change because adding a pattern restriction to a previously unrestricted parameter will reject values that were previously accepted, breaking existing clients
error at .compat-tmp/base/legacy-openapi.yaml, in API GET /api/v1/replenishment/suggestions removed the required property `requestId` from the response with the `400` status [response-required-property-removed]. 
```

### JSON Schemas de eventos - contracts/schemas/

```
Schemas comparados: base=[ninguno] head=[replenishment-risk-detected.v1.schema.json, stock-reserved.v1.schema.json]
COMPATIBLE replenishment-risk-detected.v1.schema.json (raiz): schema nuevo (sin linea base)
COMPATIBLE stock-reserved.v1.schema.json (raiz): schema nuevo (sin linea base)

Resultado: 0 cambio(s) incompatible(s).
```

**Veredicto: COMPATIBLE** (ningun cambio incompatible en v1).
<!-- compat-report:end -->
