#!/usr/bin/env node
// Renders a Valence README banner (Day and Night, 1280x320) from brand/banner.html.
//
//   node brand/render-banner.js <slug> "<Title>" "<Tagline>"
//   node brand/render-banner.js muse "Muse Connector" "Chat completions with budgets, bulk, and usage tracking"
//
// Writes brand/assets/<slug>-banner-day.png and <slug>-banner-night.png.
// Needs Playwright with Chromium (npm i -g playwright; run with NODE_PATH="$(npm root -g)")
// and network access to Google Fonts, which the banner waits for before the screenshot.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const [slug, title, tagline] = process.argv.slice(2);
if (!slug || !title || !tagline) {
  console.error('usage: node brand/render-banner.js <slug> "<Title>" "<Tagline>"');
  process.exit(2);
}

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const template = fs.readFileSync(path.join(__dirname, 'banner.html'), 'utf8');
const outDir = path.join(__dirname, 'assets');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 320 } });
  for (const theme of ['day', 'night']) {
    const html = template
      .replace('<div class="t">{{TITLE}}</div>', `<div class="t">${esc(title)}</div>`)
      .replace('<div class="d">{{TAGLINE}}</div>', `<div class="d">${esc(tagline)}</div>`)
      .replace('class="THEME"', `class="${theme}"`);
    await page.setContent(html, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    const fonts = await page.evaluate(() =>
      ['Poiret One', 'Josefin Sans'].filter((f) => !document.fonts.check(`20px "${f}"`)));
    if (fonts.length) throw new Error(`fonts did not load: ${fonts.join(', ')}`);
    const overflow = await page.evaluate(() => document.querySelector('.t').getBoundingClientRect().right);
    if (overflow > 1230) console.warn(`warning: title runs to x=${Math.round(overflow)}; shorten it to stay inside the frame`);
    const out = path.join(outDir, `${slug}-banner-${theme}.png`);
    await page.screenshot({ path: out });
    console.log(out);
  }
  await browser.close();
})().catch((e) => { console.error(e.message); process.exit(1); });
