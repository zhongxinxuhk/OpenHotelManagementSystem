#!/usr/bin/env node
/**
 * 同步业务路由的国际化词条（S3-01）。
 *
 * 路由的 `name` 会去 locales 里找 `menu.<name>` 的词条（前端规范 §3）。
 * 模板自带的 demo 词条（form / list / profile / account / result / dashboard /
 * chatbot / admin）对应的页面已删除，留着它们只会让词条表与实际菜单对不上。
 *
 * 各语言目录结构不一致（有的只有 .ts，有的有子目录），脚本统一处理两种形态。
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 仓库相对路径：原实现写的是作者本机的绝对路径，换台机器（或 CI）就找不到目录。
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const LOCALES_DIR = join(repoRoot, 'frontend', 'merchant-web', 'src', 'locales');

/** 各语言的业务菜单词条。key = 路由 name。 */
const MENU = {
  'zh-CN': {
    'menu.workspace': '工作台',
    'menu.workspace.today': '今日工作台',
    'menu.hotel': '门店',
    'menu.hotel.reservation': '订单',
    'menu.hotel.room': '房型与房间',
    'menu.hotel.inventory': '房态与可售',
    'menu.ops': '运维',
    'menu.ops.queue': '运维处置',
    'menu.login': '登录',
    'menu.exception': '异常页',
    'menu.exception.403': '无访问权限',
    'menu.exception.404': '页面不存在',
    'menu.exception.500': '服务异常',
  },
  'zh-TW': {
    'menu.workspace': '工作台',
    'menu.workspace.today': '今日工作台',
    'menu.hotel': '門店',
    'menu.hotel.reservation': '訂單',
    'menu.hotel.room': '房型與房間',
    'menu.hotel.inventory': '房態與可售',
    'menu.ops': '維運',
    'menu.ops.queue': '維運處置',
    'menu.login': '登入',
    'menu.exception': '異常頁',
    'menu.exception.403': '無存取權限',
    'menu.exception.404': '頁面不存在',
    'menu.exception.500': '服務異常',
  },
  'en-US': {
    'menu.workspace': 'Workspace',
    'menu.workspace.today': 'Today',
    'menu.hotel': 'Hotel',
    'menu.hotel.reservation': 'Reservations',
    'menu.hotel.room': 'Rooms',
    'menu.hotel.inventory': 'Availability',
    'menu.ops': 'Operations',
    'menu.ops.queue': 'Operations Queue',
    'menu.login': 'Sign in',
    'menu.exception': 'Exceptions',
    'menu.exception.403': 'Forbidden',
    'menu.exception.404': 'Not Found',
    'menu.exception.500': 'Server Error',
  },
  'ja-JP': {
    'menu.workspace': 'ワークスペース',
    'menu.workspace.today': '今日',
    'menu.hotel': 'ホテル',
    'menu.hotel.reservation': '予約',
    'menu.hotel.room': '客室タイプと客室',
    'menu.hotel.inventory': '在庫と販売可否',
    'menu.ops': '運用',
    'menu.ops.queue': '運用対応',
    'menu.login': 'ログイン',
    'menu.exception': '例外',
    'menu.exception.403': '権限なし',
    'menu.exception.404': 'ページなし',
    'menu.exception.500': 'サーバーエラー',
  },
  'pt-BR': {
    'menu.workspace': 'Área de trabalho',
    'menu.workspace.today': 'Hoje',
    'menu.hotel': 'Hotel',
    'menu.hotel.reservation': 'Reservas',
    'menu.hotel.room': 'Quartos',
    'menu.hotel.inventory': 'Disponibilidade',
    'menu.ops': 'Operações',
    'menu.ops.queue': 'Fila de operações',
    'menu.login': 'Entrar',
    'menu.exception': 'Exceções',
    'menu.exception.403': 'Sem permissão',
    'menu.exception.404': 'Página não encontrada',
    'menu.exception.500': 'Erro do servidor',
  },
  'id-ID': {
    'menu.workspace': 'Ruang kerja',
    'menu.workspace.today': 'Hari ini',
    'menu.hotel': 'Hotel',
    'menu.hotel.reservation': 'Reservasi',
    'menu.hotel.room': 'Kamar',
    'menu.hotel.inventory': 'Ketersediaan',
    'menu.ops': 'Operasional',
    'menu.ops.queue': 'Antrean operasional',
    'menu.login': 'Masuk',
    'menu.exception': 'Pengecualian',
    'menu.exception.403': 'Tidak diizinkan',
    'menu.exception.404': 'Halaman tidak ditemukan',
    'menu.exception.500': 'Galat pelayan',
  },
  'bn-BD': {
    'menu.workspace': 'কর্মক্ষেত্র',
    'menu.workspace.today': 'আজ',
    'menu.hotel': 'হোটেল',
    'menu.hotel.reservation': 'আরक्षণ',
    'menu.hotel.room': 'ঘর',
    'menu.hotel.inventory': 'প্রাপ্যতা',
    'menu.ops': 'কার্যক্রম',
    'menu.ops.queue': 'কার্যক্রম সারি',
    'menu.login': 'লগ ইন',
    'menu.exception': 'ব্যতিক্রম',
    'menu.exception.403': 'অনুমতি নেই',
    'menu.exception.404': 'পৃষ্ঠা পাওয়া যায়নি',
    'menu.exception.500': 'সার্ভার ত্রুটি',
  },
  'fa-IR': {
    'menu.workspace': 'فضای کاری',
    'menu.workspace.today': 'امروز',
    'menu.hotel': 'هتل',
    'menu.hotel.reservation': 'رزروها',
    'menu.hotel.room': 'اتاق‌ها',
    'menu.hotel.inventory': 'در دسترس بودن',
    'menu.ops': 'عملیات',
    'menu.ops.queue': 'صف عملیات',
    'menu.login': 'ورود',
    'menu.exception': 'استثناها',
    'menu.exception.403': 'دسترسی مجاز نیست',
    'menu.exception.404': 'صفحه یافت نشد',
    'menu.exception.500': 'خطای سرور',
  },
};

/** demo 路由对应的旧词条：页面已删，词条一并清掉。 */
const DEMO_KEYS = [
  'menu.welcome',
  'menu.more-blocks',
  'menu.home',
  'menu.admin',
  'menu.admin.sub-page',
  'menu.register',
  'menu.register-result',
  'menu.dashboard',
  'menu.dashboard.analysis',
  'menu.dashboard.monitor',
  'menu.dashboard.workplace',
  'menu.form',
  'menu.form.basic-form',
  'menu.form.step-form',
  'menu.form.step-form.info',
  'menu.form.step-form.confirm',
  'menu.form.step-form.result',
  'menu.form.advanced-form',
  'menu.list',
  'menu.list.table-list',
  'menu.list.basic-list',
  'menu.list.card-list',
  'menu.list.search-list',
  'menu.list.search-list.articles',
  'menu.list.search-list.projects',
  'menu.list.search-list.applications',
  'menu.profile',
  'menu.profile.basic',
  'menu.profile.advanced',
  'menu.result',
  'menu.result.success',
  'menu.result.fail',
  'menu.account',
  'menu.account.center',
  'menu.account.settings',
  'menu.account.trigger',
  'menu.account.logout',
  'menu.editor',
  'menu.editor.flow',
  'menu.editor.mind',
  'menu.editor.koni',
  'menu.chatbot',
];

function menuFilePath(localeDir) {
  const nested = join(localeDir, 'menu.ts');
  try {
    if (statSync(nested).isFile()) return nested;
  } catch {
    /* 目录形态 */
  }
  return join(localeDir, `${localeDir.split('/').pop()}.ts`);
}

for (const [locale, entries] of Object.entries(MENU)) {
  const localeDir = join(LOCALES_DIR, locale);
  const file = menuFilePath(localeDir);
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    console.log(`skip ${locale}（无 menu 词条文件）`);
    continue;
  }

  const isTs = file.endsWith(`${locale}.ts`);
  // 单文件形态（zh-CN.ts）里只有 menu 与其它段落，需要定位 menu 段落。
  const startMarker = isTs ? "export default {" : "export default {";
  const start = text.indexOf(startMarker);
  if (start < 0) {
    console.log(`skip ${locale}（未找到导出段）`);
    continue;
  }
  const bodyStart = start + startMarker.length;

  // 找到 menu 段落的结束（下一个顶层 "};" 或 "export default"）
  const end = text.indexOf('\n};', bodyStart);
  if (end < 0) {
    console.log(`skip ${locale}（未找到段落结束）`);
    continue;
  }
  const body = text.slice(bodyStart, end);

  // 逐条删除 demo 词条
  let cleaned = body;
  for (const key of DEMO_KEYS) {
    cleaned = cleaned.replace(
      new RegExp(`\\n?\\s*'${key.replace('.', '\\.')}':[^\\n]*,`, 'g'),
      '',
    );
  }
  cleaned = cleaned.replace(/^\s*\n/, '\n');

  // 追加业务词条
  const additions = Object.entries(entries)
    .map(([key, value]) => `  '${key}': '${value.replace(/'/g, "\\'")}',`)
    .join('\n');
  const newBody = `\n${additions}\n`;

  const updated = text.slice(0, bodyStart) + newBody + text.slice(end);
  writeFileSync(file, updated);
  console.log(`updated ${file}`);
}
