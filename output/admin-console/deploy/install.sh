#!/bin/bash
# Usage: bash install.sh /tmp/elma-admin-<release>; input is a reviewed release bundle.
set -euo pipefail
upload=${1:?upload directory required}
case "$upload" in /tmp/elma-admin-*) ;; *) echo INVALID_UPLOAD >&2; exit 1;; esac
release=$(basename "$upload")
base=/opt/elma-console
backup="$base/backups/$release"
code="$base/releases/$release"
site=/var/www/elma-console
snippet=/etc/nginx/snippets/elma-console.conf
unit=/etc/systemd/system/elma-console.service
test -f "$upload/static/index.html"
test -f "$upload/output/admin-console/server/app.py"
test -f /etc/nginx/elma-console.htpasswd
test ! -e "$backup"
test ! -e "$site.next"
install -d -m 0755 "$base/releases"
install -d -m 0700 "$backup"
cp -a "$site" "$backup/console"
cp -a "$snippet" "$backup/snippet.conf"
sha256sum /opt/elma-gohan/app.jar /etc/nginx/elma-console.htpasswd /etc/nginx/conf.d/blog.conf > "$backup/protected.sha256"
previous_active=false
previous_enabled=false
systemctl is-active --quiet elma-console && previous_active=true || true
systemctl is-enabled --quiet elma-console 2>/dev/null && previous_enabled=true || true
previous=""
if [ -L "$base/current" ]; then previous=$(readlink -f "$base/current"); fi
if [ -f "$unit" ]; then cp -a "$unit" "$backup/unit"; fi
if [ -f /etc/elma-console/database.env ]; then cp -a /etc/elma-console/database.env "$backup/database.env"; fi
rollback() {
  trap - ERR
  set +e
  restore_ok=true
  echo 'DEPLOY_FAILED restoring previous console' >&2
  systemctl stop elma-console 2>/dev/null
  cp -a "$backup/snippet.conf" "$snippet" || restore_ok=false
  if [ -d "$site.next" ]; then mv "$site.next" "$backup/failed-staging" || restore_ok=false; fi
  if [ -d "$site" ]; then mv "$site" "$backup/failed-console" || restore_ok=false; fi
  cp -a "$backup/console" "$site" || restore_ok=false
  if [ -n "$previous" ]; then ln -sfn "$previous" "$base/current" || restore_ok=false
  else rm -f "$base/current" || restore_ok=false; fi
  if [ -f "$backup/unit" ]; then cp -a "$backup/unit" "$unit" || restore_ok=false
  else systemctl disable elma-console 2>/dev/null; rm -f "$unit" || restore_ok=false; fi
  if [ -f "$backup/database.env" ]; then cp -a "$backup/database.env" /etc/elma-console/database.env || restore_ok=false
  else rm -f /etc/elma-console/database.env || restore_ok=false; fi
  systemctl daemon-reload || restore_ok=false
  if [ -f "$backup/unit" ]; then
    if [ "$previous_enabled" = true ]; then systemctl enable elma-console || restore_ok=false
    else systemctl disable elma-console || restore_ok=false; fi
    if [ "$previous_active" = true ]; then systemctl start elma-console || restore_ok=false; fi
  fi
  nginx -t && systemctl reload nginx || restore_ok=false
  if [ "$restore_ok" = true ]; then
    echo "ROLLBACK_OK BACKUP=$backup" >&2
    exit "${1:-1}"
  fi
  echo "ROLLBACK_NEEDS_ATTENTION BACKUP=$backup" >&2
  exit 1
}
{
  echo '#!/bin/bash'
  declare -p backup snippet site base previous unit previous_enabled previous_active
  declare -f rollback
  echo 'rollback 0'
} > "$backup/rollback.sh"
chmod 0700 "$backup/rollback.sh"
trap rollback ERR
id elma-console >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin elma-console
install -d -m 0700 -o elma-console -g elma-console /var/lib/elma-console /var/lib/elma-console/snapshots
cp -a "$upload" "$code"
find "$code" -type d -exec chmod 0755 {} +
find "$code" -type f -exec chmod 0644 {} +
if [ ! -x "$code/.venv/bin/python" ]; then
  python3 -m venv --without-pip "$code/.venv"
fi
if ! "$code/.venv/bin/python" -m pip --version >/dev/null 2>&1; then
  python3 -m pip --python "$code/.venv/bin/python" install --disable-pip-version-check -q pip
fi
"$code/.venv/bin/python" -m pip install --disable-pip-version-check -q -r "$code/output/admin-console/deploy/requirements.txt"
python3 "$code/output/admin-console/deploy/configure_environment.py"
ln -sfn "$code" "$base/current"
install -m 0644 "$code/output/admin-console/deploy/elma-console.service" "$unit"
systemctl daemon-reload
systemctl restart elma-console
ready=false
for attempt in $(seq 1 20); do
  if curl -fsS --max-time 2 -H 'Host: elma-gohan.xyz' http://127.0.0.1:8092/console/data/v1/state >/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
python3 "$code/output/admin-console/deploy/bootstrap_snapshot.py"
# Preserve the app acceptance route and an archived copy of the reviewed legacy guide.
cp -a "$site" "$site.next"
if [ ! -f "$site.next/legacy/index.html" ]; then
  install -d -m 0755 "$site.next/legacy"
  cp -a "$site/index.html" "$site.next/legacy/index.html"
  cp "$code/static/THIRD_PARTY_NOTICES.md" "$site.next/legacy/THIRD_PARTY_NOTICES.md"
fi
cp -a "$code/static/." "$site.next/"
find "$site.next" -type d -exec chmod 0755 {} +
find "$site.next" -type f -exec chmod 0644 {} +
mv "$site" "$backup/previous-console"
mv "$site.next" "$site"
install -m 0644 "$code/output/admin-console/deploy/nginx.conf" "$snippet"
nginx -t
systemctl reload nginx
for path in /console/ /console/data/v1/state /console/data/v1/snapshot /console/legacy/; do
  test "$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "https://elma-gohan.xyz$path")" = 401
done
curl -fsS --max-time 15 https://api.elma-gohan.xyz/health | grep -q '"status":"UP"'
test "$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' https://elma-gohan.xyz/)" = 200
sha256sum -c "$backup/protected.sha256"
systemctl enable elma-console
sha256sum "$site/index.html"
echo "DEPLOY_OK RELEASE=$release BACKUP=$backup"
