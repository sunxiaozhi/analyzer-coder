const { chromium } = require(process.env.QA_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const out = process.env.QA_OUTPUT || path.join(__dirname, 'results');
const url = process.env.QA_URL || 'http://127.0.0.1:5173/qa/ui-optimization.html';
if (!['127.0.0.1','localhost','[::1]'].includes(new URL(url).hostname) || !new URL(url).pathname.endsWith('/qa/ui-optimization.html')) throw new Error('QA_URL must point to the local UI fixture.');
(async () => {
  await fs.mkdir(out, { recursive:true });
  const browser = await chromium.launch({ channel:process.env.QA_BROWSER_CHANNEL || 'msedge', headless:true });
  const results = [];
  try {
    for (const width of [1440,1366,1024,390]) {
      for (const route of ['repositories','indexing','settings','accounts','audit','atlas','help','mcp','login']) {
        const page = await browser.newPage({ viewport:{ width,height:width === 390 ? 844 : width === 1440 ? 900 : 768 } });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        try {
          await page.goto(`${url}?admin#/${route}`);
          await page.locator('#app > *').first().waitFor();
          await page.waitForTimeout(600);
          await page.screenshot({ path:path.join(out,`after-${route}-${width}.png`),fullPage:true,animations:'disabled' });
          assert.deepEqual(errors, []);
          assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Page has horizontal overflow');
          results.push({ route,width,status:'PASS',errors });
          console.log('PASS',route,width);
        } catch (error) {
          results.push({ route,width,status:'FAIL',error:error.message,errors });
          console.log('FAIL',route,width,error.message);
        } finally { await page.context().close(); }
      }
    }
  } finally { await browser.close(); }
  await fs.writeFile(path.join(out,'management-layout.json'),JSON.stringify(results,null,2));
  process.exitCode = results.some(result => result.status === 'FAIL') ? 1 : 0;
})().catch(error => { console.error(error); process.exitCode = 1; });
