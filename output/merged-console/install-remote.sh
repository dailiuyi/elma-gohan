#!/bin/sh
set -eu

SITE_DIR=/var/www/elma-console
HTML_SRC=/tmp/elma-console-index.html
SNIPPET_SRC=/tmp/elma-console.snippet.conf
SNIPPET_DST=/etc/nginx/snippets/elma-console.conf
HTPASSWD=/etc/nginx/elma-console.htpasswd
BLOG_CONF=/etc/nginx/conf.d/blog.conf
INCLUDE_LINE='    include /etc/nginx/snippets/elma-console.conf;'
USER_NAME=elma

if [ ! -f "$HTML_SRC" ] || [ ! -f "$SNIPPET_SRC" ]; then
    echo 'INSTALL_FAIL missing uploaded files' >&2
    exit 1
fi

install -d -m 0755 -o root -g root "$SITE_DIR"
install -m 0644 -o root -g root "$HTML_SRC" "$SITE_DIR/index.html"
install -m 0644 -o root -g root "$SNIPPET_SRC" "$SNIPPET_DST"

if [ ! -f "$HTPASSWD" ]; then
    PASSWORD=$(openssl rand -base64 15 | tr -d '/+=' | cut -c1-16)
    HASH=$(openssl passwd -apr1 "$PASSWORD")
    printf '%s:%s\n' "$USER_NAME" "$HASH" > "$HTPASSWD"
    chmod 0640 "$HTPASSWD"
    chown root:www-data "$HTPASSWD"
    printf 'CONSOLE_PASSWORD_CREATED %s\n' "$PASSWORD"
else
    echo 'CONSOLE_PASSWORD_KEPT'
fi

if ! grep -q 'snippets/elma-console.conf' "$BLOG_CONF"; then
    cp -a "$BLOG_CONF" "$BLOG_CONF.bak-elma-console-$(date -u +%Y%m%dT%H%M%SZ)"
    python3 - "$BLOG_CONF" "$INCLUDE_LINE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
include_line = sys.argv[2]
text = path.read_text(encoding="utf-8")
old = """    error_log /var/log/nginx/blog.error.log;

    location / {
        try_files $uri $uri/ $uri.html =404;
"""
new = f"""    error_log /var/log/nginx/blog.error.log;

{include_line}

    location / {{
        try_files $uri $uri/ $uri.html =404;
"""
if old not in text:
    raise SystemExit("blog.conf HTTPS server block was not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
PY
    echo 'NGINX_SNIPPET_INCLUDED'
else
    echo 'NGINX_SNIPPET_PRESENT'
fi

nginx -t
systemctl reload nginx
echo 'INSTALL_OK'
