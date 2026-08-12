#!/usr/bin/env bash
set -euo pipefail

archive=${1:-/tmp/mailhub-governance-deploy.tar.gz}
stamp=$(date +%Y%m%d%H%M%S)
staging="/opt/mailhub.release.${stamp}"

sudo install -d -o root -g mailhub -m 0750 "$staging"
sudo tar -xzf "$archive" -C "$staging"
sudo cp -a /opt/mailhub/.env "$staging/.env"
sudo cp -a /opt/mailhub/data "$staging/data"
sudo mv /opt/mailhub "/opt/mailhub.backup.${stamp}"
sudo mv "$staging" /opt/mailhub
sudo python3 -m venv /opt/mailhub/venv
sudo /opt/mailhub/venv/bin/pip install --disable-pip-version-check \
  -r /opt/mailhub/requirements.txt pytest
sudo chown -R mailhub:mailhub /opt/mailhub/data

sudo -u mailhub bash -c \
  "cd /opt/mailhub && MAILHUB_SECRET=test MAILHUB_DB=/tmp/mailhub-update-test-${stamp}.db \
   /opt/mailhub/venv/bin/python -m pytest tests -q"

sudo install -m 0644 /opt/mailhub/deploy/mailhub.service /etc/systemd/system/mailhub.service
# Preserve the live nginx configuration, including Certbot-managed certificate paths.
# The bootstrap config is installed only on a host without an existing site file.
if [ ! -e /etc/nginx/sites-available/mailhub ]; then
  sudo install -m 0644 /opt/mailhub/deploy/nginx-mailhub.conf /etc/nginx/sites-available/mailhub
fi
sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl restart mailhub nginx
for _ in $(seq 1 15); do
  if curl -fsS http://127.0.0.1:8018/api/health; then
    printf '\nMailHub update health check passed.\n'
    exit 0
  fi
  sleep 1
done

sudo journalctl -u mailhub --no-pager -n 50
exit 1
