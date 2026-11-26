#!/usr/bin/env bash
# Reporte de compatibilidad de contratos: contracts/openapi.yaml (oasdiff) y contracts/schemas/*.schema.json
# (scripts/schema-compat.mjs) del arbol de trabajo contra una referencia base de git.
#
# Uso:   bash scripts/compat-report.sh [BASE_REF] [--update-doc]
#   BASE_REF       rama/commit base. Por defecto: origin/main, o main si no existe el remoto, o HEAD~1.
#   --update-doc   reescribe la seccion generada de contracts/COMPATIBILITY.md con el reporte.
# Variables: REPORT (salida, por defecto target/compat-report.md), OASDIFF_BIN (binario local; si no, Docker),
#            OASDIFF_IMAGE (por defecto tufin/oasdiff:v1.41.0).
# Salida: 0 sin cambios incompatibles; 1 si hay cambios incompatibles (el PR debe quedar bloqueado).
# Un cambio incompatible se publica como v2 (rutas /api/v2 y schemas *.v2.schema.json NUEVOS); v1 no se modifica.
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"
BASE_REF=""
UPDATE_DOC=0
for a in "$@"; do
  case "$a" in
    --update-doc) UPDATE_DOC=1 ;;
    *) BASE_REF="$a" ;;
  esac
done
if [ -z "$BASE_REF" ]; then
  if git rev-parse --verify -q origin/main >/dev/null; then BASE_REF=origin/main
  elif git rev-parse --verify -q main >/dev/null; then BASE_REF=main
  else BASE_REF=HEAD~1; fi
fi
git rev-parse --verify -q "$BASE_REF^{commit}" >/dev/null || { echo "Referencia base inexistente: $BASE_REF" >&2; exit 2; }

REPORT="${REPORT:-target/compat-report.md}"
OASDIFF_IMAGE="${OASDIFF_IMAGE:-tufin/oasdiff:v1.41.0}"
WORK=".compat-tmp"
rm -rf "$WORK"; mkdir -p "$WORK/base/schemas" "$(dirname "$REPORT")"
trap 'rm -rf "$WORK"' EXIT

oasdiff() {
  if [ -n "${OASDIFF_BIN:-}" ]; then "$OASDIFF_BIN" "$@"
  else MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/work" -w /work "$OASDIFF_IMAGE" "$@"; fi
}

BASE_SHA=$(git rev-parse --short "$BASE_REF")
HEAD_SHA=$(git rev-parse --short HEAD)
STATUS=0
{
  echo "## Reporte de compatibilidad de contratos"
  echo
  echo "- Base: \`$BASE_REF\` ($BASE_SHA)  -  Cambio propuesto: \`$HEAD_SHA\` + arbol de trabajo"
  echo "- Generado: $(date -u +%Y-%m-%dT%H:%M:%SZ) por \`scripts/compat-report.sh\`"
  echo
} > "$REPORT"

# ---------------------------------------------------------------- OpenAPI
echo "### OpenAPI (oasdiff) - contracts/openapi.yaml" >> "$REPORT"
if git cat-file -e "$BASE_REF:contracts/openapi.yaml" 2>/dev/null; then
  git show "$BASE_REF:contracts/openapi.yaml" > "$WORK/base/openapi.yaml"
  {
    echo; echo "**Cambios incompatibles (\`oasdiff breaking --fail-on ERR\`):**"; echo; echo '```'
  } >> "$REPORT"
  oasdiff breaking "$WORK/base/openapi.yaml" contracts/openapi.yaml --fail-on ERR > "$WORK/breaking.txt" 2>&1
  RC=$?
  [ -s "$WORK/breaking.txt" ] && cat "$WORK/breaking.txt" >> "$REPORT" || echo "(ninguno)" >> "$REPORT"
  echo '```' >> "$REPORT"
  if [ $RC -ne 0 ]; then STATUS=1; echo "OpenAPI: CAMBIOS INCOMPATIBLES (oasdiff exit $RC)"; else echo "OpenAPI: sin cambios incompatibles"; fi
  {
    echo; echo "**Changelog completo (\`oasdiff changelog\`):**"; echo; echo '```'
  } >> "$REPORT"
  oasdiff changelog "$WORK/base/openapi.yaml" contracts/openapi.yaml > "$WORK/changelog.txt" 2>&1
  [ -s "$WORK/changelog.txt" ] && cat "$WORK/changelog.txt" >> "$REPORT" || echo "(sin cambios)" >> "$REPORT"
  echo '```' >> "$REPORT"
else
  {
    echo
    echo "La base \`$BASE_REF\` no contiene \`contracts/openapi.yaml\`: es la **primera publicacion** del contrato, no hay linea base"
    echo "contra la cual comparar (resultado: sin cambios incompatibles)."
  } >> "$REPORT"
  echo "OpenAPI: primera publicacion (sin linea base en $BASE_REF)"
  # Informativo (no bloquea): diferencias respecto al openapi/openapi.yaml original, que no era un contrato publicado
  if git cat-file -e "$BASE_REF:openapi/openapi.yaml" 2>/dev/null; then
    git show "$BASE_REF:openapi/openapi.yaml" > "$WORK/base/legacy-openapi.yaml"
    {
      echo; echo "**Informativo (no bloquea)** - diferencias respecto al \`openapi/openapi.yaml\` original del curso:"; echo; echo '```'
    } >> "$REPORT"
    oasdiff breaking "$WORK/base/legacy-openapi.yaml" contracts/openapi.yaml > "$WORK/legacy.txt" 2>&1
    [ -s "$WORK/legacy.txt" ] && cat "$WORK/legacy.txt" >> "$REPORT" || echo "(ninguno)" >> "$REPORT"
    echo '```' >> "$REPORT"
  fi
fi

# ---------------------------------------------------------------- JSON Schemas
{ echo; echo "### JSON Schemas de eventos - contracts/schemas/"; echo; echo '```'; } >> "$REPORT"
for f in $(git ls-tree --name-only "$BASE_REF" contracts/schemas/ 2>/dev/null | grep '\.schema\.json$'); do
  git show "$BASE_REF:$f" > "$WORK/base/schemas/$(basename "$f")"
done
node scripts/schema-compat.mjs "$WORK/base/schemas" contracts/schemas > "$WORK/schemas.txt" 2>&1
RC=$?
cat "$WORK/schemas.txt" >> "$REPORT"
echo '```' >> "$REPORT"
if [ $RC -ne 0 ]; then STATUS=1; echo "Schemas: CAMBIOS INCOMPATIBLES"; else echo "Schemas: sin cambios incompatibles"; fi

# ---------------------------------------------------------------- Veredicto
{
  echo
  if [ $STATUS -eq 0 ]; then echo "**Veredicto: COMPATIBLE** (ningun cambio incompatible en v1)."
  else echo "**Veredicto: INCOMPATIBLE** - hay cambios que rompen consumidores de v1. Publicar como v2 (nueva ruta/routing key/schema) con convivencia y migracion; no modificar v1."; fi
} >> "$REPORT"

cat "$REPORT"

if [ $UPDATE_DOC -eq 1 ]; then
  DOC=contracts/COMPATIBILITY.md
  awk -v rep="$REPORT" '
    /<!-- compat-report:start -->/ { print; while ((getline line < rep) > 0) print line; skip=1; next }
    /<!-- compat-report:end -->/   { skip=0 }
    !skip { print }' "$DOC" > "$WORK/doc.md" && cp "$WORK/doc.md" "$DOC"
  echo "Seccion generada de $DOC actualizada."
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then cat "$REPORT" >> "$GITHUB_STEP_SUMMARY"; fi
exit $STATUS
