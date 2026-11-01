#!/usr/bin/env node
/**
 * 同步登录页国际化词条（S3-01）。
 *
 * 模板自带的词条描述的是模板登录页（手机号登录、验证码、"ant.design" 提示），
 * 而真实登录页只有租户编码 + 登录名 + 口令三个字段。
 * 词条与页面不一致会直接暴露模板残留——"密码: ant.design" 出现在
 * PMS 登录框的占位符里，任何人都看得出这是没改完的模板。
 *
 * 同时删掉不再使用的手机号/验证码/注册相关词条。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 仓库相对路径：原实现写的是作者本机的绝对路径，换台机器（或 CI）就找不到目录。
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const LOCALES_DIR = join(repoRoot, 'frontend', 'merchant-web', 'src', 'locales');

/** 各语言的登录页词条。 */
const PAGES = {
  'zh-CN': {
    'pages.login.success': '登录成功',
    'pages.login.failure': '登录失败',
    'pages.login.submit': '登录',
    'pages.login.tenantCode.placeholder': '租户编码',
    'pages.login.tenantCode.required': '请输入租户编码',
    'pages.login.username.placeholder': '登录名',
    'pages.login.username.required': '请输入登录名',
    'pages.login.password.placeholder': '口令',
    'pages.login.password.required': '请输入口令',
  },
  'zh-TW': {
    'pages.login.success': '登入成功',
    'pages.login.failure': '登入失敗',
    'pages.login.submit': '登入',
    'pages.login.tenantCode.placeholder': '租戶編碼',
    'pages.login.tenantCode.required': '請輸入租戶編碼',
    'pages.login.username.placeholder': '登入名',
    'pages.login.username.required': '請輸入登入名',
    'pages.login.password.placeholder': '密碼',
    'pages.login.password.required': '請輸入密碼',
  },
  'en-US': {
    'pages.login.success': 'Signed in',
    'pages.login.failure': 'Sign-in failed',
    'pages.login.submit': 'Sign in',
    'pages.login.tenantCode.placeholder': 'Tenant code',
    'pages.login.tenantCode.required': 'Please enter the tenant code',
    'pages.login.username.placeholder': 'Username',
    'pages.login.username.required': 'Please enter the username',
    'pages.login.password.placeholder': 'Password',
    'pages.login.password.required': 'Please enter the password',
  },
  'ja-JP': {
    'pages.login.success': 'ログイン成功',
    'pages.login.failure': 'ログイン失敗',
    'pages.login.submit': 'ログイン',
    'pages.login.tenantCode.placeholder': 'テナントコード',
    'pages.login.tenantCode.required': 'テナントコードを入力してください',
    'pages.login.username.placeholder': 'ログイン名',
    'pages.login.username.required': 'ログイン名を入力してください',
    'pages.login.password.placeholder': 'パスワード',
    'pages.login.password.required': 'パスワードを入力してください',
  },
  'pt-BR': {
    'pages.login.success': 'Login efetuado',
    'pages.login.failure': 'Falha no login',
    'pages.login.submit': 'Entrar',
    'pages.login.tenantCode.placeholder': 'Código do tenant',
    'pages.login.tenantCode.required': 'Informe o código do tenant',
    'pages.login.username.placeholder': 'Usuário',
    'pages.login.username.required': 'Informe o usuário',
    'pages.login.password.placeholder': 'Senha',
    'pages.login.password.required': 'Informe a senha',
  },
  'id-ID': {
    'pages.login.success': 'Berhasil masuk',
    'pages.login.failure': 'Gagal masuk',
    'pages.login.submit': 'Masuk',
    'pages.login.tenantCode.placeholder': 'Kode tenant',
    'pages.login.tenantCode.required': 'Masukkan kode tenant',
    'pages.login.username.placeholder': 'Nama pengguna',
    'pages.login.username.required': 'Masukkan nama pengguna',
    'pages.login.password.placeholder': 'Kata sandi',
    'pages.login.password.required': 'Masukkan kata sandi',
  },
  'bn-BD': {
    'pages.login.success': 'সফললগ',
    'pages.login.failure': 'লগ ইন ব্যর্থ',
    'pages.login.submit': 'লগ ইন',
    'pages.login.tenantCode.placeholder': 'টেন্যান্ট কোড',
    'pages.login.tenantCode.required': 'টেন্যান্ট কোড লিখুন',
    'pages.login.username.placeholder': 'লগইন নাম',
    'pages.login.username.required': 'লগইন নাম লিখুন',
    'pages.login.password.placeholder': 'পাসওয়ার্ড',
    'pages.login.password.required': 'পাসওয়ার্ড লিখুন',
  },
  'fa-IR': {
    'pages.login.success': 'ورود موفق',
    'pages.login.failure': 'ورود ناموفق',
    'pages.login.submit': 'ورود',
    'pages.login.tenantCode.placeholder': 'کد مستأجر',
    'pages.login.tenantCode.required': 'کد مستأجر را وارد کنید',
    'pages.login.username.placeholder': 'نام کاربری',
    'pages.login.username.required': 'نام کاربری را وارد کنید',
    'pages.login.password.placeholder': 'رمز عبور',
    'pages.login.password.required': 'رمز عبور را وارد کنید',
  },
};

/** 模板遗留词条：对应的界面已不存在（手机号登录、验证码、注册等）。 */
const STALE = [
  'pages.login.accountLogin.tab',
  'pages.login.accountLogin.errorMessage',
  'pages.login.phoneLogin.tab',
  'pages.login.phoneLogin.errorMessage',
  'pages.login.phoneLogin.getVerificationCode',
  'pages.login.phoneLogin.phoneError',
  'pages.login.phoneLogin.phonePlaceholder',
  'pages.login.captcha.placeholder',
  'pages.login.captcha.required',
  'pages.login.captcha.sendCaptcha',
  'pages.getCaptchaSecondText',
  'pages.login.rememberMe',
  'pages.login.forgotPassword',
  'pages.login.form.defaultLogin',
  'pages.login.form.loginWith',
  'pages.welcome.404Welcome',
  'pages.welcome.401Welcome',
  'pages.welcome.welcome',
  'pages.welcome.info',
  'pages.welcome.greet',
];

for (const [locale, entries] of Object.entries(PAGES)) {
  const file = join(LOCALES_DIR, locale, 'pages.ts');
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    console.log(`skip ${locale}`);
    continue;
  }

  const start = text.indexOf('export default {');
  const bodyStart = start + 'export default {'.length;
  const end = text.indexOf('\n};', bodyStart);
  let body = text.slice(bodyStart, end);

  // 删除模板遗留词条
  for (const key of STALE) {
    body = body.replace(
      new RegExp(`\\n?\\s*'${key.replace(/[.]/g, '\\.')}':[^\\n]*,`, 'g'),
      '',
    );
  }
  // 多行词条（如 errorMessage 折行）单独处理
  body = body.replace(/\n?\s*'pages\.login\.accountLogin\.errorMessage':\s*\n\s*'[^']*',/g, '');
  body = body.replace(/\n?\s*'pages\.welcome\.[a-zA-Z]*':\s*\n\s*'[^']*',/g, '');
  body = body.replace(/^\s*\n/, '\n');

  // 追加真实词条
  const additions = Object.entries(entries)
    .map(([key, value]) => `  '${key}': '${value.replace(/'/g, "\\'")}',`)
    .join('\n');

  const updated =
    text.slice(0, bodyStart) + `\n${additions}\n` + text.slice(end);
  writeFileSync(file, updated);
  console.log(`updated ${file}`);
}
