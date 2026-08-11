# 贡献规范

## 开始前

1. 使用 Python 3.11 或更新版本，并在虚拟环境中安装 `requirements.txt`。
2. 复制 `.env.example` 为 `.env`，仅在本机填写测试密钥。
3. 不要使用真实邮箱授权码、OAuth Token 或生产数据库运行测试。

## 提交前检查

```bash
python -m pytest tests -q
python -m py_compile *.py
node --check static/app.js
```

提交不得包含 `.env`、数据库、备份、密钥、Token、运行日志或部署暂存目录。测试必须保持临时数据库隔离。

## 设计约束

- 自动治理只允许 `label_only`，不得新增自动删除、回复、转发或退订路径。
- 邮件正文、主题、发件人和附件名都必须按不可信输入处理。
- 新增账户接入必须完成真实连接测试，并优先使用授权码或 OAuth。
- 不可逆服务器动作必须保留审计记录，并在服务器成功后才更新本地状态。
- 安全边界变化必须同步更新 [SECURITY.md](SECURITY.md)。

## 提交说明

提交消息使用动词开头并说明影响范围，例如：

```text
Add NetEase IMAP ID handshake
Fix purge consistency on server failure
Document governance fallback behavior
```
