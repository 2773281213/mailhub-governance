#!/usr/bin/env bash
# MailHub 更新：先在 staging 目录装依赖并跑测试（服务照常运行），通过后再停服务、
# 复制数据、切换目录；切换后健康检查失败会自动回滚到原版本和原数据。
set -euo pipefail

archive=${1:-/tmp/mailhub-governance-deploy.tar.gz}
stamp=$(date +%Y%m%d%H%M%S)
live=/opt/mailhub
staging="/opt/mailhub.release.${stamp}"
backup="/opt/mailhub.backup.${stamp}"

health_check() {
  for _ in $(seq 1 20); do
    if curl -fsS http://127.0.0.1:8018/api/health >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# 1. 准备新版本。systemd 用 venv/bin/python -m uvicorn 启动，不依赖脚本的 shebang，
#    所以 venv 在 staging 里创建、随目录一起搬到正式路径也能正常工作。
sudo install -d -o root -g mailhub -m 0750 "$staging"
sudo tar -xzf "$archive" -C "$staging"
# static/vendor（echarts、字体等第三方文件）不在仓库里：压缩包没带时沿用线上的那份
if [ ! -d "$staging/static/vendor" ] && sudo test -d "$live/static/vendor"; then
  sudo cp -a "$live/static/vendor" "$staging/static/vendor"
fi
sudo python3 -m venv "$staging/venv"
sudo "$staging/venv/bin/python" -m pip install --disable-pip-version-check \
  -r "$staging/requirements.txt" pytest

# 2. 在新版本上跑测试；失败时线上服务完全不受影响
test_db_dir=$(sudo -u mailhub mktemp -d)
if ! sudo -u mailhub bash -c \
  "cd '$staging' && MAILHUB_SECRET=update-check-secret-$stamp MAILHUB_DB='$test_db_dir/test.db' \
   '$staging/venv/bin/python' -m pytest tests -q -p no:cacheprovider"; then
  echo "Tests failed; the running version was not touched. Staging left at $staging" >&2
  exit 1
fi
sudo rm -rf "$test_db_dir"

# 3. 停服务后再复制数据，保证 SQLite 文件（含 WAL）是一致的快照
sudo systemctl stop mailhub
sudo cp -a "$live/.env" "$staging/.env"
sudo cp -a "$live/data" "$staging/data"
sudo chown -R mailhub:mailhub "$staging/data"
sudo mv "$live" "$backup"
sudo mv "$staging" "$live"

sudo install -m 0644 "$live/deploy/mailhub.service" /etc/systemd/system/mailhub.service
# Preserve the live nginx configuration, including Certbot-managed certificate paths.
# The bootstrap config is installed only on a host without an existing site file.
if [ ! -e /etc/nginx/sites-available/mailhub ]; then
  sudo install -m 0644 "$live/deploy/nginx-mailhub.conf" /etc/nginx/sites-available/mailhub
  domain=$(sudo sed -n 's#^MAILHUB_PUBLIC_URL=https\{0,1\}://\([A-Za-z0-9.-]*\).*#\1#p' "$live/.env")
  if [ -z "$domain" ]; then
    echo "MAILHUB_PUBLIC_URL is missing from $live/.env" >&2
    exit 1
  fi
  sudo sed -i "s/__MAILHUB_DOMAIN__/${domain}/g" /etc/nginx/sites-available/mailhub
fi
sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl start mailhub
sudo systemctl reload nginx

if health_check; then
  printf 'MailHub update health check passed. Previous version kept at %s\n' "$backup"
  exit 0
fi

# 4. 回滚：恢复旧代码和更新前的数据（新版本可能已经迁移过数据库结构）
echo "Health check failed; rolling back to $backup" >&2
sudo journalctl -u mailhub --no-pager -n 50 || true
sudo systemctl stop mailhub || true
sudo mv "$live" "/opt/mailhub.failed.${stamp}"
sudo mv "$backup" "$live"
sudo install -m 0644 "$live/deploy/mailhub.service" /etc/systemd/system/mailhub.service
sudo systemctl daemon-reload
sudo systemctl start mailhub
if health_check; then
  echo "Rolled back to the previous version. Failed release kept at /opt/mailhub.failed.${stamp}" >&2
else
  echo "Rollback health check also failed; check 'journalctl -u mailhub'" >&2
fi
exit 1
