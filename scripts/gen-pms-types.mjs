#!/usr/bin/env node
/**
 * 从 OpenAPI 契约生成前端类型（S3-01）。
 *
 * 为什么用脚本而不是手写类型：前端规范 §2 要求"类型来自契约，不手写重复定义"。
 * 手写的类型会在后端改字段后静默漂移——TypeScript 不会报错，页面在运行时才炸。
 * 这里从 docs/api/openapi-v1.json 生成，契约本身由后端的 OpenApiContractTest 守护，
 * 两端因此共用同一份事实源。
 *
 * 用法：
 *   node scripts/gen-pms-types.mjs                          # 默认输出 merchant-web
 *   node scripts/gen-pms-types.mjs frontend/platform-admin   # 指定前端工程（R-20261102-02 起多端）
 *   node scripts/gen-pms-types.mjs --check [工程]            # 只校验是否已是最新（CI 用）
 *
 * 多端各自生成到自己的 src/services/pms/types.ts（ADR-017：类型从契约生成，不手写、不共享包）。
 * 契约文件路径：仓库根的 docs/api/openapi-v1.json。
 * 重新导出契约的方法见该文件顶部的说明（后端 /v3/api-docs）。
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// 脚本位于 <repo>/scripts/，因此仓库根是上一级（而不是两级）。
const repoRoot = resolve(here, '..');
const contractPath = join(repoRoot, 'docs', 'api', 'openapi-v1.json');

// 目标工程：位置参数（如 frontend/platform-admin）；缺省为 merchant-web。
const targetArg = process.argv.slice(2).filter((a) => a !== '--check')[0];
const outputPath = join(
  repoRoot,
  targetArg ?? 'frontend/merchant-web',
  'src',
  'services',
  'pms',
  'types.ts',
);

if (!existsSync(contractPath)) {
  console.error(`找不到契约文件：${contractPath}`);
  process.exit(1);
}

const contract = JSON.parse(readFileSync(contractPath, 'utf8'));
const schemas = contract.components?.schemas ?? {};

/** 把 OpenAPI 的 schema 映射成 TypeScript 类型表达式。 */
function tsType(schema) {
  if (!schema) return 'unknown';
  if (schema.$ref) return schema.$ref.split('/').pop();
  if (schema.oneOf || schema.anyOf) {
    const parts = (schema.oneOf ?? schema.anyOf).map(tsType);
    return [...new Set(parts)].join(' | ');
  }
  switch (schema.type) {
    case 'array':
      return `${tsType(schema.items)}[]`;
    case 'integer':
    case 'number':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'string':
      return 'string';
    default:
      return 'unknown';
  }
}

/** 必填字段不带 `?`，可选字段带 `?`——必填性是契约的一部分，不能丢。 */
function renderSchema(name, schema) {
  const properties = schema.properties ?? {};
  const required = new Set(schema.required ?? []);
  const keys = Object.keys(properties).sort();

  if (keys.length === 0) {
    return `Record<string, never>`;
  }

  const lines = ['{'];
  for (const key of keys) {
    const optional = required.has(key) ? '' : '?';
    lines.push(`  ${key}${optional}: ${tsType(properties[key])};`);
  }
  lines.push('}');
  return lines.join('\n');
}

const header = `/**
 * 由 OpenAPI 契约生成的类型 —— 不要手改。
 *
 * 契约来源：docs/api/openapi-v1.json（后端 /v3/api-docs 导出，由 OpenApiContractTest 守护）。
 * 重新生成：node scripts/gen-pms-types.mjs
 *
 * 字段可选性来自契约的 required 列表：必填字段在 TS 里是必填的，
 * 可选字段带 ? —— 与后端的校验规则一致，避免前端以为可以不传。
 *
 * 每个 schema 单独导出成具名 type（而不是塞进一个大 interface）：
 * 前者可以被别的类型互相引用，后者在 interface 内部无法自引用。
 */
`;

// 每个 schema 一条独立 type；引用关系通过名字直接连上。
const typeBlocks = Object.keys(schemas)
  .sort()
  .map((name) => `export type ${name} = ${renderSchema(name, schemas[name])};`)
  .join('\n\n');

const output = `${header}\n${typeBlocks}\n`;

if (process.argv.includes('--check')) {
  const current = existsSync(outputPath) ? readFileSync(outputPath, 'utf8') : '';
  if (current !== output) {
    console.error('类型文件与契约不一致，请运行：node scripts/gen-pms-types.mjs');
    process.exit(1);
  }
  console.log('类型文件与契约一致');
} else {
  writeFileSync(outputPath, output);
  console.log(`已生成 ${outputPath}（${Object.keys(schemas).length} 个 schema）`);
}
