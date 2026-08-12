#!/usr/bin/env bash
set -euo pipefail

archive=${1:-/tmp/mailhub-governance-deploy.tar.gz}
domain=${2:-email.11451405.xyz}
stamp=$(date +%Y%m%d%H%M%S)

export DEBIAN_FRONTEND=noninteractive
sudo apt-get update
sudo apt-get install -y python3-venv python3-pip nginx certbot python3-certbot-nginx \
  curl iptables-persistent

if ! id mailhub >/dev/null 2>&1; then
  sudo useradd --system --home /opt/mailhub --shell /usr/sbin/nologin mailhub
fi
if [ -d /opt/mailhub ]; then
  sudo mv /opt/mailhub "/opt/mailhub.backup.${stamp}"
fi

sudo install -d -o root -g mailhub -m 0750 /opt/mailhub
sudo tar -xzf "$archive" -C /opt/mailhub
sudo install -d -o mailhub -g mailhub -m 0750 /opt/mailhub/data
sudo python3 -m venv /opt/mailhub/venv
sudo /opt/mailhub/venv/bin/pip install --disable-pip-version-check \
  -r /opt/mailhub/requirements.txt pytest

secret=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')
sudo install -o root -g mailhub -m 0640 /dev/null /opt/mailhub/.env
{
  printf 'MAILHUB_SECRET=%s\n' "$secret"
  printf 'MAILHUB_DB=/opt/mailhub/data/mailhub.db\n'
  printf 'MAILHUB_PUBLIC_URL=https://%s\n' "$domain"
  printf 'MICROSOFT_CLIENT_ID=\nMICROSOFT_CLIENT_SECRET=\n'
  printf 'GOOGLE_CLIENT_ID=\nGOOGLE_CLIENT_SECRET=\n'
} | sudo tee /opt/mailhub/.env >/dev/null

sudo -u mailhub bash -c \
  "cd /opt/mailhub && MAILHUB_SECRET=test MAILHUB_DB=/tmp/mailhub-test-${stamp}.db \
   /opt/mailhub/venv/bin/python -m pytest tests -q"

sudo install -m 0644 /opt/mailhub/deploy/mailhub.service /etc/systemd/system/mailhub.service
sudo install -m 0644 /opt/mailhub/deploy/nginx-mailhub.conf /etc/nginx/sites-available/mailhub
sudo install -d -m 0750 /etc/nginx/ssl
if [ ! -s /etc/nginx/ssl/mailhub-origin.crt ] || [ ! -s /etc/nginx/ssl/mailhub-origin.key ]; then
  sudo openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
    -subj "/CN=${domain}" -addext "subjectAltName=DNS:${domain}" \
    -keyout /etc/nginx/ssl/mailhub-origin.key \
    -out /etc/nginx/ssl/mailhub-origin.crt >/dev/null 2>&1
  sudo chmod 0600 /etc/nginx/ssl/mailhub-origin.key
  sudo chmod 0644 /etc/nginx/ssl/mailhub-origin.crt
fi
sudo ln -sfn /etc/nginx/sites-available/mailhub /etc/nginx/sites-enabled/mailhub
sudo rm -f /etc/nginx/sites-enabled/default
sudo iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || \
  sudo iptables -I INPUT 5 -p tcp --dport 80 -j ACCEPT
sudo iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || \
  sudo iptables -I INPUT 6 -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save >/dev/null
sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl enable --now mailhub nginx
sudo systemctl restart nginx

for _ in $(seq 1 15); do
  if curl -fsS http://127.0.0.1:8018/api/health; then
    printf '\nMailHub health check passed.\n'
    exit 0
  fi
  sleep 1
done

sudo journalctl -u mailhub --no-pager -n 50
exit 1
