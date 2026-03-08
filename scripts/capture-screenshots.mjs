import { chromium } from 'playwright';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(__dirname, '..', 'docs', 'screenshots');
const BASE = 'https://polaris-frontend-production.up.railway.app';

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();

const shot = async (name) => {
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: false });
  console.log(`  saved ${name}.png`);
};

// ── LOGIN PAGE ──────────────────────────────────────────────────────────────
console.log('📸 login...');
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await shot('login');

// ── ADMIN LOGIN ─────────────────────────────────────────────────────────────
console.log('Logging in as admin...');
await page.getByRole('radio', { name: 'Admin' }).click();
await page.getByRole('textbox', { name: 'Username' }).fill('admin');
await page.getByRole('textbox', { name: 'Password' }).fill('Admin@123');
await page.getByRole('button', { name: 'Sign In' }).click();

// Wait for navigation away from login
await page.waitForFunction(() => !window.location.href.includes('/login'), { timeout: 15000 });
await page.waitForLoadState('networkidle');
console.log('Navigated to:', page.url());

// ── ADMIN DASHBOARD (first tab) ─────────────────────────────────────────────
console.log('📸 admin-dashboard...');
// Click the first tab (Dashboard)
const tabs = page.getByRole('tab');
await tabs.first().click();
await page.waitForLoadState('networkidle');
await shot('admin-dashboard');

// ── API KEYS TAB ─────────────────────────────────────────────────────────────
console.log('📸 admin-api-keys...');
const allTabs = await tabs.all();
console.log(`  found ${allTabs.length} tabs`);
for (const tab of allTabs) {
  const text = await tab.textContent();
  console.log('  tab:', text?.trim());
}
// Try to find and click API Keys tab
const apiKeysTab = page.getByRole('tab').filter({ hasText: /api key/i });
if (await apiKeysTab.count() > 0) {
  await apiKeysTab.first().click();
  await page.waitForLoadState('networkidle');
  await shot('admin-api-keys');
}

// ── RATE POLICIES TAB ────────────────────────────────────────────────────────
console.log('📸 admin-rate-policies...');
const ratePoliciesTab = page.getByRole('tab').filter({ hasText: /rate/i });
if (await ratePoliciesTab.count() > 0) {
  await ratePoliciesTab.first().click();
  await page.waitForLoadState('networkidle');
  await shot('admin-rate-policies');
}

// ── SYSTEM HEALTH TAB ────────────────────────────────────────────────────────
console.log('📸 admin-system-health...');
const systemHealthTab = page.getByRole('tab').filter({ hasText: /health|system/i });
if (await systemHealthTab.count() > 0) {
  await systemHealthTab.first().click();
  await page.waitForLoadState('networkidle');
  await shot('admin-system-health');
}

// ── LOGOUT ───────────────────────────────────────────────────────────────────
console.log('Logging out...');
// Try to find logout - could be a button or link
const logoutEl = page.getByRole('button', { name: /logout/i });
if (await logoutEl.count() > 0) {
  await logoutEl.first().click();
  await page.waitForLoadState('networkidle');
} else {
  // Navigate manually
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
}

// ── USER LOGIN ───────────────────────────────────────────────────────────────
console.log('Logging in as user...');
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.getByRole('radio', { name: 'User' }).click();
await page.getByRole('textbox', { name: 'Username' }).fill('user');
await page.getByRole('textbox', { name: 'Password' }).fill('User@123');
await page.getByRole('button', { name: 'Sign In' }).click();
await page.waitForFunction(() => !window.location.href.includes('/login'), { timeout: 15000 });
await page.waitForLoadState('networkidle');
console.log('Navigated to:', page.url());

// ── USER DASHBOARD (first tab) ───────────────────────────────────────────────
console.log('📸 user-dashboard...');
const userTabs = page.getByRole('tab');
await userTabs.first().click();
await page.waitForLoadState('networkidle');
await shot('user-dashboard');

// List all user tabs
const userTabsList = await userTabs.all();
for (const tab of userTabsList) {
  const text = await tab.textContent();
  console.log('  user tab:', text?.trim());
}

// ── USER — API KEY LOGIN TAB ──────────────────────────────────────────────────
const apiKeyLoginTab = page.getByRole('tab').filter({ hasText: /api key/i });
if (await apiKeyLoginTab.count() > 0) {
  await apiKeyLoginTab.first().click();
  await page.waitForLoadState('networkidle');
  console.log('📸 user-api-key-login...');
  await shot('user-api-key-login');
}

// ── USER SIMULATOR TAB ───────────────────────────────────────────────────────
const simTab = page.getByRole('tab').filter({ hasText: /simulat/i });
if (await simTab.count() > 0) {
  await simTab.first().click();
  await page.waitForLoadState('networkidle');
  console.log('📸 user-simulator...');
  await shot('user-simulator');
}

await browser.close();
console.log('✅ Done!');
