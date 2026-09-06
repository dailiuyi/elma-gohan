#!/bin/bash
# Input: isolated upload directory containing app.jar, console/ and snippet.conf.
# Existing credentials and blog root remain managed outside this release.
set -euo pipefail
upload=${1:?upload directory required}
case "$upload" in /opt/elma-gohan/incoming/evidence-*) ;; *) echo 'Invalid upload directory' >&2; exit 1;; esac
test -f "$upload/app.jar"
test -f "$upload/console/index.html"
test -f "$upload/console/app/index.html"
test -f /etc/nginx/elma-console.htpasswd
test ! -e /var/www/elma-console.next
release=$(basename "$upload")
backup="/opt/elma-gohan/backups/$release"
test ! -e "$backup"
install -d -m 0700 "$backup"
cp -a /opt/elma-gohan/app.jar "$backup/app.jar"
cp -a /etc/nginx/snippets/elma-console.conf "$backup/snippet.conf"
cp -a /var/www/elma-console "$backup/console"
sha256sum /etc/nginx/elma-console.htpasswd /etc/nginx/conf.d/blog.conf > "$backup/protected.sha256"
runuser -u postgres -- pg_dump -Fc elma > "$backup/database.dump"
test -s "$backup/database.dump"
chmod 0600 "$backup/database.dump"
rollback() {
    trap - ERR
    echo 'RELEASE_FAILED restoring previous app and console' >&2
    cp -a "$backup/app.jar" /opt/elma-gohan/app.jar.rollback
    mv -f /opt/elma-gohan/app.jar.rollback /opt/elma-gohan/app.jar
    cp -a "$backup/snippet.conf" /etc/nginx/snippets/elma-console.conf
    if [ -e /var/www/elma-console ]; then mv /var/www/elma-console "$backup/failed-console"; fi
    cp -a "$backup/console" /var/www/elma-console
    nginx -t && systemctl reload nginx
    systemctl restart elma-gohan
    echo "BACKUP=$backup (additive V10 retained; no automatic database restore)" >&2
    exit 1
}
trap rollback ERR
# Stage the new site before swapping so its assets and HTML always agree.
cp -a "$upload/console" /var/www/elma-console.next
find /var/www/elma-console.next -type d -exec chmod 0755 {} +
find /var/www/elma-console.next -type f -exec chmod 0644 {} +
mv /var/www/elma-console "$backup/previous-console"
mv /var/www/elma-console.next /var/www/elma-console
install -m 0644 "$upload/snippet.conf" /etc/nginx/snippets/elma-console.conf
nginx -t
systemctl reload nginx
install -m 0644 "$upload/app.jar" /opt/elma-gohan/app.jar.next
mv -f /opt/elma-gohan/app.jar.next /opt/elma-gohan/app.jar
systemctl restart elma-gohan
healthy=false
for attempt in $(seq 1 45); do
    if curl --silent --fail --max-time 2 http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"'; then healthy=true; break; fi
    sleep 2
done
test "$healthy" = true
sha256sum -c "$backup/protected.sha256"
sha256sum /opt/elma-gohan/app.jar /var/www/elma-console/index.html
echo "RELEASE_OK $release BACKUP=$backup"
