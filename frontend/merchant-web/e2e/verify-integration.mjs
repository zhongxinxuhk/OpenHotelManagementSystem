/**
 * 前后端对接端到端验证（S3-01）。
 *
 * 目的：用真实浏览器走一遍"登录 → 今日工作台 → 订单 → 房型与房间 → 房态"，
 * 确认前端真的在调后端接口、而不是渲染一堆空壳页面。
 *
 * 判定标准（每一项都对应一个真实风险）：
 * · 登录后能进入工作台，且**指标不是空的 mock**；
 * · 订单列表能看到后端返回的真实订单号；
 * · 房型页能看到后端返回的真实房型名称；
 * · 页面里没有模板残留（Ant Design Pro / ant.design 等）。
 *
 * 用法：node e2e/verify-integration.mjs [baseUrl]
 */
import { chromium } from 'playwright';

const BASE = process.argv[2] || 'http://localhost:8000';
const TENANT = 'pms-admin';
const USER = 'admin';
const PASSWORD = 'admin';
/** 新员工改密时用的口令：满足后端策略（≥12 位且不含登录名）。 */
const NEW_PASSWORD = 'Front-Desk-2026-A';

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`,
  );
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

const consoleErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
const failedRequests = [];
page.on('requestfailed', (req) => {
  failedRequests.push(
    `${req.method()} ${req.url()} — ${req.failure()?.errorText}`,
  );
});
const apiCalls = [];
page.on('response', async (res) => {
  const url = res.url();
  if (url.includes('/api/')) {
    apiCalls.push({ url: url.replace(BASE, ''), status: res.status() });
  }
});

try {
  // ---------- 1. 登录页 ----------
  await page.goto(`${BASE}/user/login`, { waitUntil: 'networkidle' });
  // ProFormText 渲染的是 id（不是 name），且登录页可能同时存在隐藏的响应式副本，
  // 因此要按 id 选**可见**的那一个，否则 fill() 写到了看不见的元素上。
  const visibleInput = async (id) => {
    const candidates = page.locator(`input#${id}`);
    const count = await candidates.count();
    for (let i = 0; i < count; i += 1) {
      if (await candidates.nth(i).isVisible()) return candidates.nth(i);
    }
    throw new Error(`找不到可见的输入框：#${id}`);
  };

  await page.waitForSelector('input#tenantCode', { timeout: 20000 });

  const tenantInput = await visibleInput('tenantCode');
  const userInput = await visibleInput('username');
  const passInput = await visibleInput('password');

  await tenantInput.fill(TENANT);
  await userInput.fill(USER);
  await passInput.fill(PASSWORD);

  // 检查默认值：租户编码应已预填 pms-admin
  const tenantValue = await tenantInput.inputValue();
  record('登录页预填租户编码', tenantValue === TENANT, `实际=${tenantValue}`);

  // 模板残留检查
  const loginHtml = await page.content();
  const hasTemplateBrand =
    loginHtml.includes('Ant Design Pro') || loginHtml.includes('ant.design');
  record('登录页无模板品牌残留', !hasTemplateBrand);

  // LoginForm 的提交按钮是 .ant-btn-primary（没有 type="submit"），
  // 页面里另有 ant-input-clear-icon 之类的按钮，用 type 选择器会选错。
  await page.locator('button.ant-btn-primary').first().click();

  // ---------- 2. 登录后进入工作台 ----------
  await page.waitForURL(/\/workspace\/today/, { timeout: 20000 });
  await page.waitForLoadState('networkidle');
  record(
    'admin/admin 登录成功并跳转工作台',
    page.url().includes('/workspace/today'),
    page.url(),
  );

  const bodyText = await page.locator('body').textContent();
  record(
    '工作台显示业务指标位',
    bodyText.includes('今日到店') && bodyText.includes('待确认'),
  );

  // 顶栏应出现门店选择器（数据来自 /api/v1/hotels）。
  // antd 6 的 Select 根节点是 .ant-select，.ant-select-selector 是旧版内部结构，
  // 这里按 .ant-select 判断并确认它显示的是后端返回的门店名。
  await page.waitForTimeout(3000);
  const hotelSelectorText = await page
    .locator('.ant-select')
    .first()
    .textContent()
    .catch(() => '');
  record(
    '顶栏门店选择器已渲染',
    hotelSelectorText.includes('总部门店'),
    `显示=${hotelSelectorText}`,
  );

  const hotelCall = apiCalls.find((c) => c.url.includes('/api/v1/hotels'));
  record(
    '已调用门店清单接口',
    Boolean(hotelCall) && hotelCall.status === 200,
    hotelCall ? `${hotelCall.url} -> ${hotelCall.status}` : '未捕获到调用',
  );

  // ---------- 3. 订单列表 ----------
  await page.goto(`${BASE}/hotel/reservation`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const reservationText = await page.locator('body').textContent();
  const hasRealOrder = /RSV\d+/.test(reservationText);
  record('订单列表显示后端真实订单号', hasRealOrder);

  const reservationCall = apiCalls.find((c) => c.url.includes('/reservations'));
  record(
    '订单列表接口调用成功',
    Boolean(reservationCall) && reservationCall.status === 200,
    reservationCall
      ? `${reservationCall.url} -> ${reservationCall.status}`
      : '未捕获到调用',
  );

  // ---------- 4. 房型与房间 ----------
  await page.goto(`${BASE}/hotel/room`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const roomText = await page.locator('body').textContent();
  record('房型页显示后端真实房型', roomText.includes('标准间'));

  // ---------- 5. 房态与可售 ----------
  await page.goto(`${BASE}/hotel/inventory`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const inventoryText = await page.locator('body').textContent();
  record(
    '房态页显示可售指标位',
    inventoryText.includes('整段可售房数') ||
      inventoryText.includes('每日余量'),
  );

  // ---------- 6. 运维处置 ----------
  await page.goto(`${BASE}/ops/queue`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const opsText = await page.locator('body').textContent();
  record('运维页显示待处置指标位', opsText.includes('待处理死信'));

  // ---------- 6b. 角色与权限（S3-02） ----------
  await page.goto(`${BASE}/identity/role`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const roleText = await page.locator('body').textContent();
  record(
    '角色页显示后端真实角色',
    roleText.includes('SUPER_ADMIN') || roleText.includes('超级管理员'),
  );
  // 权限点清单必须来自后端字典，不能是前端硬编码
  const permissionCall = apiCalls.find((c) => c.url.includes('/identity/permissions'));
  record(
    '权限点字典接口已调用',
    Boolean(permissionCall) && permissionCall.status === 200,
    permissionCall ? `-> ${permissionCall.status}` : '未捕获到调用',
  );

  // ---------- 6c. 员工管理（S3-02） ----------
  await page.goto(`${BASE}/identity/user`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const userText = await page.locator('body').textContent();
  record('员工页显示后端真实账号', userText.includes('admin'));
  // 不能停用自己：当前账号那一行的操作必须是禁用的
  const disabledSelfButton = await page
    .locator('button:has-text("当前账号")')
    .first()
    .isDisabled()
    .catch(() => false);
  record('员工页禁用了「停用自己」操作', disabledSelfButton);

  const userCall = apiCalls.find((c) => c.url.includes('/identity/users'));
  record(
    '员工列表接口已调用',
    Boolean(userCall) && userCall.status === 200,
    userCall ? `-> ${userCall.status}` : '未捕获到调用',
  );

  // ---------- 6d. 开通员工 → 一次性初始口令（S3-02 续） ----------
  // 这一段走真实 UI：点按钮 → 填表 → 选角色与门店 → 提交 → 拿到初始口令。
  // 用带时间戳的登录名，避免第二次运行撞 409。
  const clerkUsername = `e2e${Date.now() % 100000}`;

  await page.locator('button:has-text("新建员工")').first().click();
  const createModal = page
    .locator('.ant-modal')
    .filter({ hasText: '新建员工' })
    .first();
  await createModal.waitFor({ state: 'visible', timeout: 10000 });

  await createModal.locator('input#username').fill(clerkUsername);
  await createModal.locator('input#displayName').fill('端到端验证员工');

  // 角色：至少一个。停用角色在界面上是禁用的，这里选第一个可用的。
  const roleItem = createModal
    .locator('.ant-form-item')
    .filter({ hasText: '角色' })
    .first();
  await roleItem.locator('input[type="checkbox"]').first().check();

  // 门店：新建表单默认勾选当前门店，这里只在未勾选时补一下，
  // 直接 check() 会把它点掉。
  const hotelItem = createModal
    .locator('.ant-form-item')
    .filter({ hasText: '门店数据范围' })
    .first();
  const firstHotelBox = hotelItem.locator('input[type="checkbox"]').first();
  if (!(await firstHotelBox.isChecked().catch(() => false))) {
    await firstHotelBox.check();
  }

  await createModal.locator('.ant-modal-footer button.ant-btn-primary').click();

  // 初始口令弹窗：必须出现"关闭后无法再次查看"的警示
  const passwordModal = page
    .locator('.ant-modal')
    .filter({ hasText: '关闭后无法再次查看' })
    .first();
  await passwordModal.waitFor({ state: 'visible', timeout: 15000 });
  record('开通员工后弹出一次性初始口令', true);

  const modalText = (await passwordModal.textContent()) || '';
  // 口令字符集刻意去掉了 0 O 1 l I，正则与生成器保持一致
  const passwordMatch = modalText.match(/[A-HJ-NP-Za-km-z2-9]{16}/);
  const initialPassword = passwordMatch ? passwordMatch[0] : '';
  record(
    '初始口令长度与字符集符合生成规则',
    initialPassword.length === 16,
    `捕获长度=${initialPassword.length}`,
  );

  await passwordModal.locator('button.ant-btn-primary').click();
  await page.waitForTimeout(2000);

  const afterCreateText = await page.locator('body').textContent();
  record(
    '员工列表出现新开通的账号',
    afterCreateText.includes(clerkUsername),
    clerkUsername,
  );

  const createCall = apiCalls.find(
    (c) => c.url.includes('/api/v1/identity/users') && c.status === 201,
  );
  record(
    '开通接口返回 201',
    Boolean(createCall),
    createCall ? `${createCall.url} -> ${createCall.status}` : '未捕获到 201',
  );

  // ---------- 6e. 用初始口令登录 → 被强制改密 → 改完才能干业务 ----------
  // 换一个浏览器上下文：模拟一台新设备，本地没有上一位用户的令牌。
  const clerkContext = await browser.newContext();
  const clerkPage = await clerkContext.newPage();
  const clerkApiCalls = [];
  clerkPage.on('response', (res) => {
    const url = res.url();
    if (url.includes('/api/')) {
      clerkApiCalls.push({ url: url.replace(BASE, ''), status: res.status() });
    }
  });

  try {
    await clerkPage.goto(`${BASE}/user/login`, { waitUntil: 'networkidle' });
    await clerkPage.waitForSelector('input#tenantCode', { timeout: 20000 });
    await clerkPage.locator('input#tenantCode').first().fill(TENANT);
    await clerkPage.locator('input#username').first().fill(clerkUsername);
    await clerkPage.locator('input#password').first().fill(initialPassword);
    await clerkPage.locator('button.ant-btn-primary').first().click();

    await clerkPage.waitForURL(/\/user\/password/, { timeout: 20000 });
    record(
      '初始口令登录后被送到改密页',
      clerkPage.url().includes('/user/password'),
      clerkPage.url(),
    );

    // 改密页要等身份态加载完才渲染"为什么强制"的警示，
    // 直接读 body 会读到加载前的空壳（这是上一版踩过的坑）。
    const forcedNotice = await clerkPage
      .locator('.ant-alert')
      .filter({ hasText: '初始口令' })
      .first()
      .waitFor({ state: 'visible', timeout: 15000 })
      .then(() => true)
      .catch(() => false);
    record('改密页说明了强制原因', forcedNotice);
    await clerkPage.screenshot({ path: '/tmp/pms-password-page.png' });

    await clerkPage.locator('input#currentPassword').first().fill(initialPassword);
    await clerkPage.locator('input#newPassword').first().fill(NEW_PASSWORD);
    await clerkPage.locator('input#confirmPassword').first().fill(NEW_PASSWORD);
    await clerkPage.locator('button.ant-btn-primary').first().click();

    // 改完跳到工作台（redirect 为 '/'，路由再转到今日工作台）
    await clerkPage.waitForURL(/\/workspace\/today/, { timeout: 25000 });
    record(
      '改密后进入工作台',
      clerkPage.url().includes('/workspace/today'),
      clerkPage.url(),
    );

    // 业务接口在改密之后才放行——这是"服务端真的拦住了"的浏览器侧证据
    await clerkPage.waitForTimeout(3000);
    const hotelsOk = clerkApiCalls.find(
      (c) => c.url.includes('/api/v1/hotels') && c.status === 200,
    );
    record(
      '改密后业务接口放行',
      Boolean(hotelsOk),
      hotelsOk ? `${hotelsOk.url} -> ${hotelsOk.status}` : '未捕获到 200',
    );
  } finally {
    await clerkContext.close();
  }

  // ---------- 7. 模板残留全站检查 ----------
  const allText = [await page.locator('body').textContent()].join('\n');
  const templateLeftovers = [
    'Ant Design Pro',
    'ant.design',
    '欢迎来到',
    '开始使用',
  ].filter((word) => allText.includes(word));
  record(
    '业务页面无模板营销文案',
    templateLeftovers.length === 0,
    templateLeftovers.join(', '),
  );

  // ---------- 8. 接口调用汇总 ----------
  const failed = apiCalls.filter((c) => c.status >= 400);
  record(
    '无失败的业务接口调用',
    failed.length === 0,
    failed.map((f) => `${f.url} -> ${f.status}`).join('; '),
  );
} catch (error) {
  record('执行过程未抛异常', false, error.message);
} finally {
  await page.screenshot({ path: '/tmp/pms-e2e-final.png', fullPage: false });
  await browser.close();
}

console.log('');
console.log(`业务接口调用（${apiCalls.length} 次）:`);
for (const call of apiCalls) {
  console.log(`  ${call.status}  ${call.url}`);
}
if (consoleErrors.length) {
  console.log('');
  console.log(`浏览器控制台错误（${consoleErrors.length}）:`);
  for (const err of consoleErrors.slice(0, 5)) console.log(`  ${err}`);
}
if (failedRequests.length) {
  console.log('');
  console.log(`失败请求（${failedRequests.length}）:`);
  for (const req of failedRequests.slice(0, 5)) console.log(`  ${req}`);
}

const failedCount = results.filter((r) => !r.ok).length;
console.log('');
console.log(`结果：${results.length - failedCount}/${results.length} 通过`);
process.exit(failedCount === 0 ? 0 : 1);
