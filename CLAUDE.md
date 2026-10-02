# Project conventions

## Default theme: Valence — Miami Deco

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
