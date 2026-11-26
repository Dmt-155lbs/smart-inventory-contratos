#!/usr/bin/env node
// Compatibilidad de JSON Schemas de eventos: compara los *.schema.json de una carpeta BASE (rama base)
// contra los de HEAD (cambio propuesto) y clasifica las diferencias.
//
//   node scripts/schema-compat.mjs <baseDir> <headDir> [--json]
//
// BREAKING (exit 1): propiedad quitada, required quitado o agregado, tipo cambiado, const cambiado,
//                    valor de enum quitado, additionalProperties true -> false, archivo v1 eliminado.
// COMPATIBLE:        propiedad opcional nueva, archivos nuevos (p. ej. un *.v2.schema.json).
// WARNING:           valor de enum agregado, pattern/format cambiado (revisar a mano).
//
// Un cambio incompatible NO se hace sobre el archivo v1: se publica un schema v2 nuevo (archivo nuevo, sin
// linea base => se reporta como COMPATIBLE) y v1 queda intacto durante la convivencia.
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const [baseDir, headDir, flag] = process.argv.slice(2);
if (!baseDir || !headDir) {
  console.error('uso: schema-compat.mjs <baseDir> <headDir> [--json]');
  process.exit(2);
}

const list = (d) => (existsSync(d) ? readdirSync(d).filter((f) => f.endsWith('.schema.json')).sort() : []);

/** Inlinea $ref locales (#/$defs/x) para comparar el esquema efectivo. */
function resolve(node, root, depth = 0) {
  if (depth > 20 || node === null || typeof node !== 'object') return node;
  if (Array.isArray(node)) return node.map((n) => resolve(n, root, depth + 1));
  if (typeof node.$ref === 'string' && node.$ref.startsWith('#/')) {
    const target = node.$ref.slice(2).split('/').reduce((acc, k) => acc?.[k.replace(/~1/g, '/').replace(/~0/g, '~')], root);
    const { $ref, ...rest } = node;
    return resolve({ ...(target ?? {}), ...rest }, root, depth + 1);
  }
  const out = {};
  for (const [k, v] of Object.entries(node)) if (k !== '$defs') out[k] = resolve(v, root, depth + 1);
  return out;
}

const typeOf = (s) => JSON.stringify(s?.type ?? null);
const changes = [];
const add = (severity, file, path, msg) => changes.push({ severity, file, path: path || '(raiz)', msg });

function walk(file, path, b, h) {
  if (!b || !h || typeof b !== 'object' || typeof h !== 'object') return;
  if (typeOf(b) !== typeOf(h)) add('BREAKING', file, path, `tipo cambiado ${typeOf(b)} -> ${typeOf(h)}`);
  if ('const' in b && JSON.stringify(b.const) !== JSON.stringify(h.const)) {
    add('BREAKING', file, path, `const cambiado ${JSON.stringify(b.const)} -> ${JSON.stringify(h.const)}`);
  }
  if (b.enum) {
    const he = h.enum ?? [];
    for (const v of b.enum) if (!he.includes(v)) add('BREAKING', file, path, `valor de enum quitado: ${JSON.stringify(v)}`);
    for (const v of he) if (!b.enum.includes(v)) add('WARNING', file, path, `valor de enum agregado: ${JSON.stringify(v)} (los consumidores deben tolerarlo)`);
  }
  for (const k of ['pattern', 'format']) {
    if (b[k] !== h[k]) add('WARNING', file, path, `${k} cambiado ${b[k]} -> ${h[k]}`);
  }
  if (b.additionalProperties !== false && h.additionalProperties === false) {
    add('BREAKING', file, path, 'additionalProperties pasa a false (rechaza campos que antes aceptaba)');
  }
  const br = new Set(b.required ?? []);
  const hr = new Set(h.required ?? []);
  for (const r of br) if (!hr.has(r)) add('BREAKING', file, path, `required quitado: ${r}`);
  for (const r of hr) if (!br.has(r)) add('BREAKING', file, path, `required agregado: ${r}`);

  const bp = b.properties ?? {};
  const hp = h.properties ?? {};
  for (const name of Object.keys(bp)) {
    if (!(name in hp)) add('BREAKING', file, `${path}/${name}`, 'propiedad quitada o renombrada');
    else walk(file, `${path}/${name}`, bp[name], hp[name]);
  }
  for (const name of Object.keys(hp)) {
    if (!(name in bp) && !hr.has(name)) add('COMPATIBLE', file, `${path}/${name}`, 'propiedad opcional nueva');
  }
  if (b.items && h.items) walk(file, `${path}[]`, b.items, h.items);
}

const baseFiles = list(baseDir);
const headFiles = list(headDir);
for (const f of baseFiles) {
  if (!headFiles.includes(f)) {
    add('BREAKING', f, '', 'schema publicado eliminado (una version publicada no se retira sin periodo de convivencia)');
    continue;
  }
  const b = JSON.parse(readFileSync(join(baseDir, f), 'utf8'));
  const h = JSON.parse(readFileSync(join(headDir, f), 'utf8'));
  walk(f, '', resolve(b, b), resolve(h, h));
}
for (const f of headFiles) if (!baseFiles.includes(f)) add('COMPATIBLE', f, '', 'schema nuevo (sin linea base)');

const breaking = changes.filter((c) => c.severity === 'BREAKING');
if (flag === '--json') {
  console.log(JSON.stringify({ baseFiles, headFiles, changes, breaking: breaking.length }, null, 2));
} else {
  console.log(`Schemas comparados: base=[${baseFiles.join(', ') || 'ninguno'}] head=[${headFiles.join(', ')}]`);
  if (changes.length === 0) console.log('Sin diferencias.');
  for (const c of changes) console.log(`${c.severity.padEnd(10)} ${c.file} ${c.path}: ${c.msg}`);
  console.log(`\nResultado: ${breaking.length} cambio(s) incompatible(s).`);
}
process.exit(breaking.length > 0 ? 1 : 0);
