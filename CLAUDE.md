# Project conventions

## Default theme: Valence — Miami Deco

Valence is the collection name and visual style. Public docs (READMEs, docs site) use the look and may call the collection "Valence", but don't explain the brand or the theme. The brand guide in `brand/` is for maintainers and isn't linked from public docs or published to the site. The site's **Style** page (`site/style.md`) shows the appearance (colors, type, diagram styles, components) without brand narrative. Its swatches and status colors are generated from `brand/tokens.json` at build time.

Every visual in this repository uses the Valence theme unless the user says otherwise. That covers Mermaid diagrams, README banners, generated HTML pages, artifacts, UI and docs. The source of truth is [`brand/`](brand/README.md):

- Colors, type and usage rules come from `brand/tokens.json`. Don't invent new colors.
- **Mermaid:** the first line of every diagram is `brand/mermaid-init.txt`. Flowcharts end with `brand/mermaid-classes.txt` and tag nodes:
  - `focus`: the subject of the diagram.
  - `decision`: decisions and branches.
  - `highlight`: human steps and callouts.
  - `external`: external systems.
  - `error`: error paths, always with "error" in the label.
  Wrap sequence diagrams in `rect rgb(251, 246, 238)` … `end`.
- **HTML / UI:** use `brand/valence.css` (Miami Day by default, Miami Night for dark).
- **Type:** Poiret One for the wordmark and display only; Josefin Sans for headings and labels; system UI sans for body text and data.
- **Text-safe colors** on Sand: Ink, Flamingo, Ocean Drive Teal. Flamingo Pastel, Seafoam and Sunset Lemon are fills only; Deco Gold is for lines and large text.
- **Status** (healthy / warning / serious / critical) uses the fixed status colors, never brand colors, and never color alone: always with an icon and a label.
- **New README:** start it with a Valence banner (see `brand/README.md` → Repository page).

## Docs site (GitHub Pages)

`.github/workflows/pages.yml` publishes https://brianbrinley.github.io/BoomiCustomConnectors/ on every merge to `main`:
- `site/build.py` renders the READMEs listed in its `PAGES` with `site/template.html`, `brand/valence.css` and `site/assets/`.
- Mermaid diagrams render in the browser and switch to `brand/mermaid-init-night.txt` in Night mode.

To publish a new README as a page (e.g. a new connector), add it to `PAGES`, plus `DOWNLOADS` if it ships a CAR. Keep README links relative: the build rewrites them for the site and sends anything not on the site to GitHub.

Check locally with `pip install -r site/requirements.txt && python site/build.py --out _site`, then open `_site/index.html` through a local web server.
