// Valence site: Day/Night toggle, theme-aware banners and Mermaid diagrams.
import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';

const root = document.documentElement;
const media = window.matchMedia('(prefers-color-scheme: dark)');
const NIGHT_INIT = window.VALENCE_NIGHT_INIT;
const DAY_RECT = /rect rgb\(251, 246, 238\)/g;
const NIGHT_RECT = 'rect rgb(31, 36, 64)';

function isNight() {
  const t = root.getAttribute('data-theme');
  return t ? t === 'dark' : media.matches;
}

// <picture> sources follow the OS setting, so pick the image explicitly when the user overrides it.
function syncPictures() {
  document.querySelectorAll('picture').forEach((pic) => {
    const img = pic.querySelector('img');
    const dark = pic.querySelector('source[media*="dark"]');
    if (!img || !dark) return;
    if (!img.dataset.day) img.dataset.day = img.getAttribute('src');
    if (!pic.dataset.night) pic.dataset.night = dark.getAttribute('srcset');
    dark.setAttribute('media', 'not all');
    img.setAttribute('src', isNight() ? pic.dataset.night : img.dataset.day);
  });
}

async function renderDiagrams() {
  const nodes = [...document.querySelectorAll('pre.mermaid')];
  if (!nodes.length) return;
  const night = isNight();
  for (const node of nodes) {
    if (!node.dataset.source) node.dataset.source = node.textContent;
    let src = node.dataset.source;
    if (night && NIGHT_INIT) {
      src = src.replace(/^\s*%%\{init:[\s\S]*?\}%%/, NIGHT_INIT).replace(DAY_RECT, NIGHT_RECT);
    }
    node.removeAttribute('data-processed');
    node.textContent = src;
  }
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict' });
  await mermaid.run({ nodes });
}

function update() {
  syncPictures();
  renderDiagrams();
}

document.querySelector('.theme-toggle')?.addEventListener('click', () => {
  const next = isNight() ? 'light' : 'dark';
  root.setAttribute('data-theme', next);
  try { localStorage.setItem('valence-theme', next); } catch (e) { /* storage unavailable */ }
  update();
});
media.addEventListener('change', () => { if (!root.getAttribute('data-theme')) update(); });

update();
