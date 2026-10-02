"""Build the GitHub Pages docs site from the repository READMEs.

Usage: python site/build.py [--out _site]

Each README listed in PAGES becomes a page. Relative links are rewritten so that links to other pages and to
copied files work on the site, and anything else points at the file on GitHub. Mermaid code blocks are rendered
in the browser (site/assets/site.js), switching to the Night theme with the page.
"""
import argparse
import datetime
import html
import json
import os
import posixpath
import re
import shutil
import subprocess

import markdown

REPO = "brianbrinley/BoomiCustomConnectors"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# source README -> output directory ("" is the site root), nav label (None: built but not in the top nav)
PAGES = [
    ("README.md", "", "Home"),
    ("jev-connector/README.md", "jev-connector", "JEV Connector"),
    ("site/style.md", "style", None),
]
# Copied verbatim to the same path on the site (only what the pages use)
COPY_PATHS = ["brand/valence.css", "brand/assets"]
# Built connector files: (glob directory, filename regex, published name prefix)
DOWNLOADS = {
    "jev-connector": [
        ("jev-connector/target", r"jev-connector-.*-car\.zip$"),
        ("jev-connector/src/main/resources", r"connector-descriptor\.xml$"),
    ],
}


def git(*args):
    try:
        return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def page_url(out_dir):
    return out_dir + "/" if out_dir else ""


def rel(from_dir, target):
    """Relative URL from a page directory to a site path."""
    r = posixpath.relpath(target or ".", from_dir or ".")
    return "" if r == "." else r


def rewrite_links(body, src_dir, out_dir, page_dirs, site_files):
    def fix(match):
        attr, quote, url = match.group(1), match.group(2), match.group(3)
        if re.match(r"^([a-z][a-z0-9+.-]*:|#|//)", url, re.I):
            return match.group(0)
        path, _, frag = url.partition("#")
        frag = "#" + frag if frag else ""
        target = posixpath.normpath(posixpath.join(src_dir, path)) if path else src_dir
        target = "" if target == "." else target
        if target.endswith("README.md"):
            target = posixpath.dirname(target)
        if target in page_dirs:
            new = rel(out_dir, target) + ("/" if target and rel(out_dir, target) else "")
            new = new or "./"
        elif target in site_files:
            new = rel(out_dir, target)
        else:
            full = os.path.join(ROOT, target)
            kind = "tree" if os.path.isdir(full) else "blob"
            new = f"https://github.com/{REPO}/{kind}/main/{target}"
        return f'{attr}={quote}{new}{frag}{quote}'

    # Leave code samples exactly as written
    parts = re.split(r"(<pre[^>]*>.*?</pre>|<code>.*?</code>)", body, flags=re.S)
    return "".join(part if part.startswith(("<pre", "<code>")) else
                   re.sub(r'\b(href|src|srcset)=(["\'])([^"\']+)\2', fix, part)
                   for part in parts)


def render(md_text):
    md = markdown.Markdown(extensions=["tables", "fenced_code", "toc", "sane_lists"],
                           extension_configs={"toc": {"permalink": "#", "permalink_class": "anchor",
                                                      "permalink_title": "Link to this section"}})
    body = md.convert(md_text)
    # Mermaid blocks render client-side
    body = re.sub(r'<pre><code class="language-mermaid">(.*?)</code></pre>',
                  lambda m: f'<pre class="mermaid">{m.group(1)}</pre>', body, flags=re.S)
    body = re.sub(r"(<table>.*?</table>)", r'<div class="table-wrap">\1</div>', body, flags=re.S)
    return body


STATUS_ICONS = {
    "healthy": '<path d="M5 12.5l4.5 4.5L19 7.5" />',
    "warning": '<path d="M12 4 L21 20 H3 Z" /><path d="M12 10v4" /><path d="M12 17v.5" />',
    "serious": '<path d="M12 3l9 9-9 9-9-9z" /><path d="M12 8v5" /><path d="M12 16v.5" />',
    "critical": '<circle cx="12" cy="12" r="9" /><path d="M9 9l6 6M15 9l-6 6" />',
}


def swatches_html(theme_name, theme):
    panel = theme["background"]["value"]
    ink = theme["text"]["value"]
    muted = theme["mutedText"]["value"]
    cards = []
    for token in theme.values():
        cards.append(
            '<figure class="swatch">'
            f'<div class="swatch-chip" style="background:{token["value"]}"></div>'
            f'<figcaption><strong style="color:{ink}">{html.escape(token["name"])}</strong>'
            f'<code style="color:{ink}">{token["value"].upper()}</code>'
            f'<span style="color:{muted}">{html.escape(token["use"])}</span></figcaption></figure>')
    return (f'<div class="swatch-panel" style="background:{panel}" role="group" '
            f'aria-label="{theme_name.title()} colors"><div class="swatches">{"".join(cards)}</div></div>')


def status_html(status):
    items = []
    for name, color in status.items():
        if name.startswith("_"):
            continue
        items.append(
            f'<div class="status-pill" style="border-color:{color}">'
            f'<span class="status-icon" style="background:{color}"><svg viewBox="0 0 24 24" width="16" height="16" '
            f'aria-hidden="true" fill="none" stroke="#0B0B0B" stroke-width="2.4" stroke-linecap="round" '
            f'stroke-linejoin="round">{STATUS_ICONS.get(name, "")}</svg></span>'
            f'<span class="status-name">{name.title()}</span><code>{color.upper()}</code></div>')
    return f'<div class="status-row">{"".join(items)}</div>'


def first_text(pattern, text, default):
    m = re.search(pattern, text, re.M)
    return re.sub(r"[*_`\[\]]|\(.*?\)", "", m.group(1)).strip() if m else default


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=os.path.join(ROOT, "_site"))
    out = parser.parse_args().out
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    # Static files
    shutil.copytree(os.path.join(ROOT, "site", "assets"), os.path.join(out, "assets"))
    for path in COPY_PATHS:
        src, dest = os.path.join(ROOT, path), os.path.join(out, path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if os.path.isdir(src):
            shutil.copytree(src, dest)
        else:
            shutil.copy(src, dest)
    downloads = {}
    for out_dir, specs in DOWNLOADS.items():
        for src_dir, pattern in specs:
            full = os.path.join(ROOT, src_dir)
            for name in sorted(os.listdir(full)) if os.path.isdir(full) else []:
                if re.match(pattern, name):
                    dest = posixpath.join(out_dir, "downloads", name)
                    os.makedirs(os.path.join(out, posixpath.dirname(dest)), exist_ok=True)
                    shutil.copy(os.path.join(full, name), os.path.join(out, dest))
                    downloads.setdefault(out_dir, []).append(dest)

    site_files = set()
    for base, _, files in os.walk(out):
        for f in files:
            site_files.add(posixpath.relpath(os.path.join(base, f), out).replace(os.sep, "/"))
    page_dirs = {d for _, d, _ in PAGES}

    template = open(os.path.join(ROOT, "site", "template.html"), encoding="utf-8").read()
    night_init = open(os.path.join(ROOT, "brand", "mermaid-init-night.txt"), encoding="utf-8").read().strip()
    mermaid_init = open(os.path.join(ROOT, "brand", "mermaid-init.txt"), encoding="utf-8").read().strip()
    mermaid_classes = open(os.path.join(ROOT, "brand", "mermaid-classes.txt"), encoding="utf-8").read()
    with open(os.path.join(ROOT, "brand", "tokens.json"), encoding="utf-8") as fh:
        tokens = json.load(fh)
    sha = git("rev-parse", "HEAD") or "main"
    built = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    for src, out_dir, label in PAGES:
        text = open(os.path.join(ROOT, src), encoding="utf-8").read()
        text = (text.replace("%%MERMAID_INIT%%", mermaid_init)
                .replace("%%MERMAID_CLASSES%%", mermaid_classes.rstrip("\n")))
        src_dir = posixpath.dirname(src)
        body = render(text)
        body = re.sub(r'<div data-swatches="(\w+)"></div>',
                      lambda m: swatches_html(m.group(1), tokens["themes"][m.group(1)]), body)
        body = body.replace("<div data-status></div>", status_html(tokens["status"]))
        if downloads.get(out_dir):
            buttons = []
            for path in downloads[out_dir]:
                name = posixpath.basename(path)
                cls = "button" if name.endswith(".zip") else "button secondary"
                what = "Connector archive (CAR)" if name.endswith(".zip") else "Connector descriptor"
                buttons.append(f'<a class="{cls}" href="{rel(out_dir, path)}" download>{what} · {html.escape(name)}</a>')
            block = ('<div class="downloads" role="group" aria-label="Downloads">' + "".join(buttons)
                     + "</div><p><small>Built from the latest commit on main. Upload both files to your Boomi "
                     "connector group.</small></p>")
            # After the first heading
            body = re.sub(r"(</h1>)", r"\1" + block.replace("\\", "\\\\"), body, count=1)
        body = rewrite_links(body, src_dir, out_dir, page_dirs, site_files)

        nav = "\n".join(
            f'    <a href="{rel(out_dir, d) + "/" if rel(out_dir, d) else "./"}"'
            + (' aria-current="page"' if d == out_dir else "") + f">{html.escape(l)}</a>"
            for _, d, l in PAGES if l)
        root_prefix = rel(out_dir, "")
        root_prefix = root_prefix + "/" if root_prefix else ""
        page = (template
                .replace("{{title}}", html.escape(first_text(r"^#\s+(.+)$", text, label or out_dir)))
                .replace("{{description}}", html.escape(first_text(r"^(?![#<|`!\s-])(.{20,}?)$", text, label or out_dir)[:200]))
                .replace("{{nav}}", nav)
                .replace("{{root}}", root_prefix)
                .replace("{{night_init}}", json.dumps(night_init))
                .replace("{{source}}", src)
                .replace("{{source_dir}}", src_dir)
                .replace("{{sha}}", sha)
                .replace("{{sha_short}}", sha[:7])
                .replace("{{built}}", built)
                .replace("{{content}}", body))
        dest = os.path.join(out, out_dir, "index.html")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        open(dest, "w", encoding="utf-8").write(page)
        print(f"built {posixpath.join(out_dir, 'index.html')} from {src}")

    open(os.path.join(out, ".nojekyll"), "w").close()


if __name__ == "__main__":
    main()
