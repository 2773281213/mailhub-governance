#!/usr/bin/env bash
set -euo pipefail

: "${MAILHUB_ADMIN_HASH:?MAILHUB_ADMIN_HASH is required}"
database=/opt/mailhub/data/mailhub.db
backup="${database}.password-backup.$(date +%Y%m%d%H%M%S)"

sudo cp -a "$database" "$backup"
sudo -u mailhub env MAILHUB_ADMIN_HASH="$MAILHUB_ADMIN_HASH" \
  /opt/mailhub/venv/bin/python - <<'PY'
import os
import sqlite3

value = os.environ["MAILHUB_ADMIN_HASH"]
if not value.startswith("pbkdf2$"):
    raise SystemExit("invalid password hash")
db = sqlite3.connect("/opt/mailhub/data/mailhub.db")
db.execute(
    "INSERT INTO settings(k,v) VALUES('admin_hash',?) "
    "ON CONFLICT(k) DO UPDATE SET v=excluded.v",
    (value,),
)
db.commit()
stored = db.execute("SELECT v FROM settings WHERE k='admin_hash'").fetchone()
if not stored or stored[0] != value:
    raise SystemExit("password hash verification failed")
print("updated and verified")
PY

printf 'backup=%s\n' "$backup"
