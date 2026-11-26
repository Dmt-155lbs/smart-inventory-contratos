#!/usr/bin/env bash
# Valida los contratos (lo mismo que el job `lint` de .github/workflows/contracts.yml).
# Uso: bash scripts/validate-contracts.sh [openapi|asyncapi|schemas|examples|all]   (por defecto: all)
# Requiere node/npx. Falla (exit != 0) ante cualquier discrepancia.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

REDOCLY="@redocly/cli@1.34.24"
ASYNCAPI="@asyncapi/cli@6.3.1"
AJV=(npx --yes -p ajv-cli@5.0.0 -p ajv-formats@3.0.1 ajv)
AJV_OPTS=(--spec=draft2020 -c ajv-formats --strict=false)
FAIL=0

openapi()  { echo "== redocly lint contracts/openapi.yaml"; npx --yes "$REDOCLY" lint contracts/openapi.yaml --max-problems 0 || FAIL=1; }
asyncapi() { echo "== asyncapi validate contracts/asyncapi.yaml"; npx --yes "$ASYNCAPI" validate contracts/asyncapi.yaml --fail-severity warn || FAIL=1; }

schemas() {
  echo "== ajv compile (draft 2020-12) de cada schema"
  for s in contracts/schemas/*.schema.json; do
    "${AJV[@]}" compile "${AJV_OPTS[@]}" -s "$s" || FAIL=1
  done
}

# Cada ejemplo se valida contra el schema de su evento: valido => debe pasar; invalido => debe FALLAR.
examples() {
  echo "== ajv validate: ejemplos validos deben pasar; invalidos deben fallar"
  declare -A SCHEMA_OF=( [stock-reserved]=stock-reserved.v1.schema.json [replenishment-risk-detected]=replenishment-risk-detected.v1.schema.json )
  for key in "${!SCHEMA_OF[@]}"; do
    schema="contracts/schemas/${SCHEMA_OF[$key]}"
    for ex in contracts/examples/"$key".v1*.json; do
      "${AJV[@]}" validate "${AJV_OPTS[@]}" -s "$schema" -d "$ex" || { echo "ERROR: el ejemplo valido $ex no valida"; FAIL=1; }
    done
    for ex in contracts/examples/invalid/"$key".*.json; do
      if "${AJV[@]}" validate "${AJV_OPTS[@]}" -s "$schema" -d "$ex" >/dev/null 2>&1; then
        echo "ERROR: el ejemplo INVALIDO $ex fue aceptado por el schema"; FAIL=1
      else
        echo "$ex: rechazado, como se esperaba"
      fi
    done
  done
}

case "${1:-all}" in
  openapi) openapi ;;
  asyncapi) asyncapi ;;
  schemas) schemas ;;
  examples) examples ;;
  all) openapi; asyncapi; schemas; examples ;;
  *) echo "uso: $0 [openapi|asyncapi|schemas|examples|all]"; exit 2 ;;
esac
[ $FAIL -eq 0 ] && echo "OK: contratos validos" || echo "FALLO: hay discrepancias en los contratos"
exit $FAIL
