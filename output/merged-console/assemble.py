#!/usr/bin/env python3
"""Assemble the two reviewed pages into one tabbed HTML file.

The product demo and operations dashboard keep their original CSS, copy, and
scripts. This file only wraps them with a thin pane switcher and scopes each
stylesheet so the two designs do not leak into each other.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
PRODUCT = ROOT / "output" / "product-demo" / "index.html"
OPS_PUBLIC = ROOT / "output" / "database-guide" / "index.html"
OPS_LOCAL = ROOT / "output" / "database-guide" / "dashboard.local.html"
OUTPUT = Path(__file__).resolve().parent / "index.html"

ROOT_SELECTORS = {
    ":root",
    "html",
    "body",
    "html, body",
    "html,body",
    "html, body ",
}


def _skip_comment_or_string(css: str, i: int) -> int | None:
    if css.startswith("/*", i):
        end = css.find("*/", i + 2)
        return len(css) if end < 0 else end + 2
    quote = css[i]
    if quote in {'"', "'"}:
        j = i + 1
        while j < len(css):
            if css[j] == "\\":
                j += 2
                continue
            if css[j] == quote:
                return j + 1
            j += 1
        return len(css)
    return None


def _matching_brace(css: str, open_at: int) -> int:
    depth = 0
    i = open_at
    while i < len(css):
        skipped = _skip_comment_or_string(css, i)
        if skipped is not None:
            i = skipped
            continue
        char = css[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced CSS brace")


def _iter_rules(css: str):
    i = 0
    n = len(css)
    while i < n:
        while i < n and css[i].isspace():
            i += 1
        if i >= n:
            break
        skipped = _skip_comment_or_string(css, i)
        if skipped is not None:
            i = skipped
            continue
        start = i
        while i < n:
            skipped = _skip_comment_or_string(css, i)
            if skipped is not None:
                i = skipped
                continue
            if css[i] == "{":
                break
            i += 1
        if i >= n:
            break
        prelude = css[start:i].strip()
        close = _matching_brace(css, i)
        body = css[i + 1 : close]
        i = close + 1
        yield prelude, body


def _prefix_selector(selector: str, pane_id: str) -> str:
    compact = re.sub(r"\s+", " ", selector).strip()
    scope = f"#{pane_id}"
    if compact in ROOT_SELECTORS:
        return scope
    if compact == "*":
        return f"{scope}, {scope} *"
    parts = [item.strip() for item in selector.split(",") if item.strip()]
    if parts and all(part == "*" or part.startswith("*::") for part in parts):
        return ", ".join([scope] + [f"{scope} {part}" for part in parts])
    prefixed = []
    for part in parts:
        if part in ROOT_SELECTORS:
            prefixed.append(scope)
        else:
            prefixed.append(f"{scope} {part}")
    return ", ".join(prefixed)


def _scope_rule_list(css: str, pane_id: str) -> str:
    parts: list[str] = []
    for prelude, body in _iter_rules(css):
        if prelude.startswith("@keyframes"):
            parts.append(f"{prelude} {{{body}}}")
            continue
        if prelude.startswith("@media"):
            parts.append(f"{prelude} {{\n{_scope_rule_list(body, pane_id)}\n}}")
            continue
        if prelude.startswith("@"):
            parts.append(f"{prelude} {{{body}}}")
            continue
        parts.append(f"{_prefix_selector(prelude, pane_id)} {{{body}}}")
    return "\n".join(parts)


def scope_css(css: str, pane_id: str) -> str:
    return _scope_rule_list(css, pane_id)


def extract_html(path: Path) -> tuple[str, str]:
    html = path.read_text(encoding="utf-8")
    style_match = re.search(r"<style>(.*?)</style>", html, re.S)
    body_match = re.search(r"<body>(.*?)</body>", html, re.S)
    if not style_match or not body_match:
        raise SystemExit(f"could not parse {path}")
    return style_match.group(1), body_match.group(1).strip()


SWITCHER_CSS = """
    html { scroll-behavior: smooth; }
    html, body { margin: 0; min-height: 100%; }
    body[data-elma-pane="product"] {
      color-scheme: light;
      background: #f5f2e9;
    }
    body[data-elma-pane="ops"] {
      color-scheme: light dark;
      background: #f5f7fb;
    }
    @media (prefers-color-scheme: dark) {
      body[data-elma-pane="ops"] { background: #10141c; }
    }
    .elma-switch {
      position: sticky;
      top: 0;
      z-index: 50;
      display: flex;
      justify-content: center;
      padding: 8px 16px;
      border-bottom: 1px solid rgba(24, 32, 24, .12);
      backdrop-filter: blur(14px);
    }
    body[data-elma-pane="product"] .elma-switch {
      background: rgba(245, 242, 233, .92);
    }
    body[data-elma-pane="ops"] .elma-switch {
      background: color-mix(in srgb, #f5f7fb 88%, white);
    }
    @media (prefers-color-scheme: dark) {
      body[data-elma-pane="ops"] .elma-switch {
        background: color-mix(in srgb, #10141c 88%, #171d27);
        border-bottom-color: #303a49;
      }
    }
    .elma-switch-group {
      display: inline-flex;
      gap: 4px;
      padding: 3px;
      border: 1px solid rgba(24, 32, 24, .13);
      border-radius: 999px;
      background: rgba(255,255,255,.72);
    }
    body[data-elma-pane="ops"] .elma-switch-group {
      border-color: #d9e0eb;
    }
    @media (prefers-color-scheme: dark) {
      body[data-elma-pane="ops"] .elma-switch-group {
        border-color: #303a49;
        background: #171d27;
      }
    }
    .elma-switch button {
      border: 0;
      background: transparent;
      border-radius: 999px;
      padding: 6px 14px;
      color: #647064;
      font: 700 12px Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
      cursor: pointer;
    }
    .elma-switch button[aria-selected="true"] {
      background: #182018;
      color: #fff;
    }
    body[data-elma-pane="ops"] .elma-switch button[aria-selected="true"] {
      background: #315be8;
    }
    @media (prefers-color-scheme: dark) {
      body[data-elma-pane="ops"] .elma-switch button { color: #aeb9ca; }
      body[data-elma-pane="ops"] .elma-switch button[aria-selected="true"] {
        background: #84a0ff;
        color: #10141c;
      }
    }
    .elma-switch button:focus-visible {
      outline: 3px solid rgba(82, 110, 232, .35);
      outline-offset: 2px;
    }
    .elma-pane[hidden] { display: none !important; }
    #elma-product, #elma-ops { min-height: calc(100vh - 42px); box-sizing: border-box; }
"""

SWITCHER_SCRIPT = """
    (function () {
      const productHashes = new Set(['', '#', '#top', '#product', '#pipeline', '#explain', '#algorithm', '#evidence', '#engineering']);
      const buttons = document.querySelectorAll('.elma-switch [data-pane]');
      const productPane = document.getElementById('elma-product');
      const opsPane = document.getElementById('elma-ops');

      function paneFromHash() {
        return location.hash === '#ops' ? 'ops' : 'product';
      }

      function show(pane) {
        const changed = document.body.dataset.elmaPane !== pane;
        document.body.dataset.elmaPane = pane;
        productPane.hidden = pane !== 'product';
        opsPane.hidden = pane !== 'ops';
        buttons.forEach((button) => {
          button.setAttribute('aria-selected', button.dataset.pane === pane ? 'true' : 'false');
        });
        if (changed) {
          document.documentElement.scrollTop = 0;
          document.body.scrollTop = 0;
        }
      }

      buttons.forEach((button) => {
        button.addEventListener('click', () => {
          const pane = button.dataset.pane;
          if (pane === 'ops') location.hash = 'ops';
          else if (!productHashes.has(location.hash) || location.hash === '#ops') location.hash = 'product';
          show(pane);
        });
      });

      window.addEventListener('hashchange', () => show(paneFromHash()));
      show(paneFromHash());
    })();
"""


def choose_ops(from_public: bool) -> Path:
    if from_public or not OPS_LOCAL.exists():
        return OPS_PUBLIC
    return OPS_LOCAL


def build(ops_path: Path) -> str:
    product_css, product_body = extract_html(PRODUCT)
    ops_css, ops_body = extract_html(ops_path)
    product_css = re.sub(r"\n\s*html\s*\{\s*scroll-behavior:\s*smooth;\s*\}", "\n", product_css, count=1)
    ops_css = re.sub(r"\n\s*html\s*\{\s*scroll-behavior:\s*smooth;\s*\}", "\n", ops_css, count=1)
    source_note = ops_path.name
    return f"""<!doctype html>
<!-- Generated by assemble.py; product-demo + database-guide/{source_note}. Do not edit by hand. -->
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
  <title>ELMA · 产品演示与运营数据</title>
  <style>
{SWITCHER_CSS}
{scope_css(product_css, "elma-product")}
{scope_css(ops_css, "elma-ops")}
    #elma-product .topbar {{ top: 42px; }}
  </style>
</head>
<body data-elma-pane="product">
  <div class="elma-switch" role="tablist" aria-label="页面切换">
    <div class="elma-switch-group">
      <button type="button" role="tab" data-pane="product" aria-controls="elma-product" aria-selected="true">产品演示</button>
      <button type="button" role="tab" data-pane="ops" aria-controls="elma-ops" aria-selected="false">运营数据</button>
    </div>
  </div>
  <div id="elma-product" class="elma-pane" role="tabpanel">
{product_body}
  </div>
  <div id="elma-ops" class="elma-pane" role="tabpanel" hidden>
{ops_body}
  </div>
  <script>
{SWITCHER_SCRIPT}
  </script>
</body>
</html>
"""


def parse_output(argv: list[str]) -> Path:
    if "--output" in argv:
        return Path(argv[argv.index("--output") + 1])
    return OUTPUT


def main() -> int:
    from_public = "--from-public" in sys.argv
    ops_path = choose_ops(from_public)
    output = parse_output(sys.argv)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(build(ops_path), encoding="utf-8", newline="\n")
    print(
        f"MERGED_OK {output} "
        f"product={PRODUCT.relative_to(ROOT).as_posix()} "
        f"ops={ops_path.relative_to(ROOT).as_posix()}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
