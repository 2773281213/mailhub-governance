"""导出 `providers.py` 的金标语料。

    python scripts/golden/providers.py           # 重新生成 JSON
    python scripts/golden/providers.py --check   # 只检查，不写文件

导出三组用例：

- `provider`：每个服务商的全部字段（含 `host_for` 与 `netease_authorize_url` 的派生值）；
- `detect`：每个自动识别域名的原样、大小写变体、子域名，以及没有 `@`、空串、未知域名；
- `needs_imap_id`：每个服务商 key 的正反例（含大小写变体、未知 key、空串）。

另外导出 `get` 与 `netease_keys` 的用例，保证服务商列表的顺序也被金标钉住。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402


def provider_dict(provider) -> dict:
    """一个服务商的全部字段，键名与 Providers.kt 的字段对应。"""
    secret = None
    if provider.secret is not None:
        secret = {
            "kind": provider.secret.kind,
            "input_label": provider.secret.input_label,
            "setup_url": provider.secret.setup_url,
            "setup_label": provider.secret.setup_label,
            "steps": list(provider.secret.steps),
            "prereq_links": [[title, url] for title, url in provider.secret.prereq_links],
            "troubleshoot": provider.secret.troubleshoot,
        }
    return {
        "key": provider.key,
        "label": provider.label,
        "imap_host": provider.imap_host,
        "domains": list(provider.domains),
        "imap_port": provider.imap_port,
        "secret": secret,
        "oauth": provider.oauth,
        "oauth_label": provider.oauth_label,
        "device_flow": provider.device_flow,
        "oauth_imap_host": provider.oauth_imap_host,
        "oauth_missing_hint": provider.oauth_missing_hint,
        "netease_web": provider.netease_web,
        "send_imap_id": provider.send_imap_id,
        "note": provider.note,
        # 派生值也钉住：OAuth 与授权码账户连的主机可能不同
        "host_for_oauth": provider.host_for("oauth"),
        "host_for_password": provider.host_for("password"),
        "netease_authorize_url": provider.netease_authorize_url,
    }


def detect_cases() -> list[dict]:
    """每个自动识别域名的各种写法，外加识别不出来的写法。"""
    import providers

    cases: list[dict] = []
    for provider in providers.PROVIDERS.values():
        for domain in provider.domains:
            variants = {
                f"{domain}-lower": f"user@{domain}",
                f"{domain}-upper": f"USER@{domain.upper()}",
                f"{domain}-mixed": f"User.Name+tag@{domain.title()}",
                f"{domain}-subdomain": f"user@mail.{domain}",
                f"{domain}-no-local": f"@{domain}",
                f"{domain}-two-at": f"a@b@{domain}",
            }
            for suffix, value in variants.items():
                cases.append({
                    "id": f"detect-{provider.key}-{suffix}",
                    "fn": "detect",
                    "args": {"email": value},
                    "expected": providers.detect(value),
                })
    extras = {
        "no-at": "plainaddress",
        "empty": "",
        "whitespace": "   ",
        "unknown-domain": "user@unknown.example",
        "unknown-tld-mix": "User@Unknown.Example",
        "only-at": "@",
        "trailing-space": "  user@qq.com  ",
        "uppercase-full": "USER@QQ.COM",
        "dots": "user@qq.com.example",
        # M8：`rpartition('@')[2]` 找不到分隔符时返回整个字符串，所以裸域名会被识别成服务商
        "no-at-qq-domain": "qq.com",
        "no-at-gmail-domain": "gmail.com",
        "no-at-subdomain": "mail.qq.com",
    }
    for name, value in extras.items():
        cases.append({
            "id": f"detect-extra-{name}",
            "fn": "detect",
            "args": {"email": value},
            "expected": providers.detect(value),
        })
    return cases


def needs_imap_id_cases() -> list[dict]:
    import providers

    cases: list[dict] = []
    for key in providers.PROVIDERS:
        values = {"exact": key, "upper": key.upper(), "padded": f" {key} "}
        for suffix, value in values.items():
            cases.append({
                "id": f"needs-imap-id-{key}-{suffix}",
                "fn": "needs_imap_id",
                "args": {"key": value},
                "expected": providers.needs_imap_id(value),
            })
    for name, value in {"unknown": "nonexistent", "empty": "", "blank": "   "}.items():
        cases.append({
            "id": f"needs-imap-id-{name}",
            "fn": "needs_imap_id",
            "args": {"key": value},
            "expected": providers.needs_imap_id(value),
        })
    return cases


def get_cases() -> list[dict]:
    import providers

    cases: list[dict] = []
    for key in providers.PROVIDERS:
        for suffix, value in {"exact": key, "upper": key.upper(), "padded": f"\t{key}\n"}.items():
            provider = providers.get(value)
            cases.append({
                "id": f"get-{key}-{suffix}",
                "fn": "get",
                "args": {"key": value},
                "expected": None if provider is None else provider.key,
            })
    for name, value in {"unknown": "nonexistent", "empty": "", "none": None}.items():
        provider = providers.get(value)
        cases.append({
            "id": f"get-{name}",
            "fn": "get",
            "args": {"key": value},
            "expected": None if provider is None else provider.key,
        })
    return cases


def build_cases() -> list[dict]:
    _common.prepare_imports()
    import providers

    cases: list[dict] = []
    for provider in providers.PROVIDERS.values():
        cases.append({
            "id": f"provider-{provider.key}",
            "fn": "provider",
            "args": {"key": provider.key},
            "expected": provider_dict(provider),
        })
    cases += detect_cases()
    cases += needs_imap_id_cases()
    cases += get_cases()
    cases.append({
        "id": "netease-keys",
        "fn": "netease_keys",
        "args": {},
        "expected": list(providers.netease_keys()),
    })
    cases.append({
        "id": "provider-order",
        "fn": "provider_order",
        "args": {},
        "expected": [provider.key for provider in providers.PROVIDERS.values()],
    })
    return cases


if __name__ == "__main__":
    _common.run("providers", build_cases)
