"""Build the Valence-themed GitHub Pages site from the repository READMEs.

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

# source README -> output directory ("" is the site root), nav label
PAGES = [
    ("README.md", "", "Home"),
    ("jev-connector/README.md", "jev-connector", "JEV Connector"),
    ("brand/README.md", "brand", "Brand"),
]
# Copied verbatim to the same path on the site
COPY_DIRS = ["brand"]
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
    for d in COPY_DIRS:
        shutil.copytree(os.path.join(ROOT, d), os.path.join(out, d))
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
    sha = git("rev-parse", "HEAD") or "main"
    built = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    for src, out_dir, label in PAGES:
        text = open(os.path.join(ROOT, src), encoding="utf-8").read()
        src_dir = posixpath.dirname(src)
        body = render(text)
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
            for _, d, l in PAGES)
        root_prefix = rel(out_dir, "")
        root_prefix = root_prefix + "/" if root_prefix else ""
        page = (template
                .replace("{{title}}", html.escape(first_text(r"^#\s+(.+)$", text, label)))
                .replace("{{description}}", html.escape(first_text(r"^(?![#<|`!\s-])(.{20,}?)$", text, label)[:200]))
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
