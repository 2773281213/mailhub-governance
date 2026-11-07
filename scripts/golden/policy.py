"""导出 policy.py（动作安全分级、受保护邮件判定）的金标语料。

Kotlin 端在 com.mailhub.core.rules.Policy 里重写，必须与这里的每条用例逐字一致，
尤其是拦截原因的中文措辞。

    python scripts/golden/policy.py           # 重新生成 JSON
    python scripts/golden/policy.py --check   # 检查是否与当前 Python 实现一致

用例来源：
- tests/test_security.py、tests/test_billing.py 里所有 policy.* 调用的输入；
- 穷举 ACTION_TIER 的每个动作 + 一个未知动作 × actor × confirmed × user_rule ×
  override_protection × 若干典型 msg（控制在 600 条以内）；
- 置信度边界值（含 f"{x:.2f}" 的进位）、全部分类的 filter_cleanable；
- importance 不是数字时 Python 抛 TypeError 的用例（expected 写成 {"raised": "TypeError"}）。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

_common.prepare_imports()

import policy  # noqa: E402

FN = {
    "is_protected": policy.is_protected,
    "evaluate": policy.evaluate,
    "filter_cleanable": policy.filter_cleanable,
}

CASES: list[dict] = []

# 未知动作按最严格处理，所以穷举时也要带上它
ACTIONS = sorted(policy.ACTION_TIER) + ["some_new_action"]
ACTORS = ["user", "rule", "ai", "system"]

# 典型 msg：受保护分类、一般账单、带附件、重要度高、VIP、已回复、普通通知、普通其他
TYPICAL_MSGS = [
    {"category": "账单"},
    {"category": "账单", "bill_tier": "一般"},
    {"category": "订阅", "has_attach": 1},
    {"category": "通知", "importance": 4},
    {"category": "验证码", "vip": True},
    {"category": "通知", "replied": True},
    {"category": "其他"},
    {"category": "重要", "importance": 5},
]


def add(fn: str, cid: str, **args) -> None:
    """按 fn 的名字调用 Python 原实现，把输入和实际输出都记下来。"""
    CASES.append({"id": cid, "fn": fn, "args": args, "expected": FN[fn](**args)})


def add_raises(fn: str, cid: str, **args) -> None:
    """记录「Python 抛 TypeError」这个结果：expected 写成 {"raised": "TypeError"}。

    Kotlin 侧看到这种 expected 就必须抛 IllegalArgumentException（对应 Python 的 TypeError），
    见 android/core/src/main/kotlin/com/mailhub/core/rules/Policy.kt 的 KDoc。
    Python 没抛说明用例前提不成立，导出时直接失败，免得金标悄悄记下一个错误的前提。
    """
    try:
        FN[fn](**args)
    except TypeError:
        CASES.append({"id": cid, "fn": fn, "args": args, "expected": {"raised": "TypeError"}})
        return
    raise AssertionError(f"{cid}：Python 没有抛 TypeError，用例前提不成立")


def evaluate(cid: str, action: str, msg: dict, *, actor: str = "system",
             confidence: float = 1.0, user_rule: bool = False,
             confirmed: bool = False, override_protection: bool = False) -> None:
    """参数按 Python evaluate 的签名铺平，缺失的用默认值补齐（金标里不省略字段）。"""
    add("evaluate", cid, action=action, msg=msg, actor=actor, confidence=confidence,
        user_rule=user_rule, confirmed=confirmed, override_protection=override_protection)


def evaluate_raises(cid: str, action: str, msg: dict, *, actor: str = "system",
                    confidence: float = 1.0, user_rule: bool = False,
                    confirmed: bool = False, override_protection: bool = False) -> None:
    """同 evaluate，但期望 Python 抛 TypeError（见 add_raises）。"""
    add_raises("evaluate", cid, action=action, msg=msg, actor=actor,
               confidence=confidence, user_rule=user_rule, confirmed=confirmed,
               override_protection=override_protection)


# ---------------- tests/test_security.py ----------------

def security_test_cases() -> None:
    evaluate("sec-purge-unconfirmed", "purge", {"category": "订阅"}, actor="user")
    evaluate("sec-purge-confirmed", "purge", {"category": "订阅"},
             actor="user", confirmed=True)

    for cat in ("账单", "安全", "重要"):
        evaluate(f"sec-protected-soft-delete-{cat}", "soft_delete", {"category": cat},
                 actor="system", user_rule=True)

    evaluate("sec-attachment-blocks-delete", "soft_delete",
             {"category": "订阅", "has_attach": 1}, actor="user", user_rule=True)
    evaluate("sec-importance-blocks-delete", "soft_delete",
             {"category": "通知", "importance": 5}, actor="user", user_rule=True)

    for action in ("soft_delete", "purge"):
        for i, msg in enumerate([{"category": "账单"},
                                 {"category": "验证码", "has_attach": 1},
                                 {"category": "通知", "importance": 5}]):
            evaluate(f"sec-confirm-does-not-skip-{action}-{i}", action, msg,
                     actor="user", confirmed=True)

    for action in ("soft_delete", "purge"):
        evaluate(f"sec-single-override-{action}", action,
                 {"category": "账单", "has_attach": 1},
                 actor="user", confirmed=True, override_protection=True)

    evaluate("sec-override-needs-user-confirm-user", "soft_delete", {"category": "账单"},
             actor="user", override_protection=True)
    evaluate("sec-override-needs-user-confirm-system", "soft_delete", {"category": "账单"},
             actor="system", user_rule=True, confirmed=True, override_protection=True)

    for action in ("soft_delete", "purge"):
        evaluate(f"sec-auto-clean-{action}", action,
                 {"category": "验证码", "has_attach": 1}, actor="system",
                 user_rule=True, confirmed=action == "purge")

    evaluate("sec-auto-clean-plain-otp-purge", "purge", {"category": "验证码"},
             actor="system", user_rule=True, confirmed=True)
    evaluate("sec-system-confirm-without-rule", "purge", {"category": "验证码"},
             actor="system", confirmed=True)
    evaluate("sec-ai-low-confidence-archive", "archive", {"category": "订阅"},
             actor="ai", confidence=0.3)
    evaluate("sec-ai-high-confidence-archive", "archive", {"category": "订阅"},
             actor="ai", confidence=0.95)
    evaluate("sec-label-always-allowed", "label", {"category": "其他"},
             actor="ai", confidence=0.1)
    evaluate("sec-unknown-action", "some_new_action", {"category": "通知"}, actor="system")

    for cat in ("账单", "安全", "重要", "可疑", "其他", "验证码", "订阅", "通知"):
        add("filter_cleanable", f"sec-cleanable-{cat}", category=cat)


# ---------------- tests/test_billing.py ----------------

def billing_test_cases() -> None:
    minor = {"category": "账单", "bill_tier": "一般"}
    add("is_protected", "bill-minor", msg=minor)
    add("is_protected", "bill-tier-important", msg={"category": "账单", "bill_tier": "重要"})
    add("is_protected", "bill-no-tier", msg={"category": "账单"})
    add("is_protected", "bill-minor-with-attachment",
        msg={"category": "账单", "bill_tier": "一般", "has_attach": 1})


# ---------------- 穷举：动作 × actor × 典型 msg ----------------

def exhaustive_cases() -> None:
    # 每个动作 × 每个 actor × 4 个典型 msg，其余开关取默认值
    for action in ACTIONS:
        for actor in ACTORS:
            for i in (0, 1, 2, 3):
                evaluate(f"x-{action}-{actor}-{i}", action, TYPICAL_MSGS[i], actor=actor)

    # confirmed × user_rule × override_protection 的三维组合，覆盖破坏性与非破坏性动作
    for action in ("purge", "soft_delete", "archive"):
        for actor in ACTORS:
            for confirmed in (False, True):
                for user_rule in (False, True):
                    for override in (False, True):
                        evaluate(
                            f"x-flags-{action}-{actor}-c{int(confirmed)}"
                            f"-r{int(user_rule)}-o{int(override)}",
                            action, {"category": "账单", "has_attach": 1}, actor=actor,
                            user_rule=user_rule, confirmed=confirmed,
                            override_protection=override)

    # 其余典型 msg 在用户/系统两条主路径上各走一遍
    for i, msg in enumerate(TYPICAL_MSGS):
        for action in ("soft_delete", "archive"):
            for actor in ("user", "system"):
                for confirmed in (False, True):
                    evaluate(f"x-msg{i}-{action}-{actor}-c{int(confirmed)}", action, msg,
                             actor=actor, user_rule=actor == "system", confirmed=confirmed)


# ---------------- 置信度边界：f"{confidence:.2f}" 的进位 ----------------

def confidence_cases() -> None:
    for confidence in (0.749, 0.75, 0.745, 0.125, 0.7449, 0.0, 0.999, 1.0):
        cid = f"{confidence:.4f}".replace(".", "_")
        evaluate(f"conf-ai-archive-{cid}", "archive", {"category": "订阅"},
                 actor="ai", confidence=confidence)
        evaluate(f"conf-ai-move-{cid}", "move", {"category": "订阅"},
                 actor="ai", confidence=confidence)
    evaluate("conf-system-archive-low", "archive", {"category": "订阅"},
             actor="system", confidence=0.7)
    evaluate("conf-ai-archive-with-rule", "archive", {"category": "订阅"},
             actor="ai", confidence=0.1, user_rule=True)
    evaluate("conf-ai-purge", "purge", {"category": "订阅"},
             actor="ai", confidence=0.1)


# ---------------- is_protected 的每个分支 ----------------

def protected_cases() -> None:
    for i, msg in enumerate([
        {"category": "账单"},
        {"category": "安全"},
        {"category": "重要"},
        {"category": "可疑"},
        {"category": "订阅"},
        {"category": "账单", "bill_tier": "一般"},
        {"category": "", "has_attach": True},
        {},
        {"category": "通知", "has_attach": 1},
        {"category": "通知", "importance": 3},
        {"category": "通知", "importance": 4},
        {"category": "通知", "vip": True},
        {"category": "通知", "replied": True},
        {"category": "通知", "vip": False, "replied": False, "has_attach": 0,
         "importance": 0},
    ]):
        add("is_protected", f"prot-{i:02d}", msg=msg)


# ---------------- importance 的类型：非数字时 Python 抛 TypeError ----------------

def importance_type_cases() -> None:
    """`(msg.get("importance") or 0) >= 4` 的取值类型。

    importance 是「真值但不是数字」（例如字符串 "5"）时，Python 的 `>= 4` 抛 TypeError，
    异常一路冒到调用方，裁决拿不到结果，删除动作因此被阻断——这是安全侧的结果。
    Kotlin 侧对应抛 IllegalArgumentException（见 Policy.kt 的 KDoc），所以这里导出「抛错」这个结果。
    """
    broken = {"category": "通知", "importance": "5"}
    # is_protected 直接抛
    add_raises("is_protected", "importance-str-is-protected", msg=broken)
    # 破坏性动作先过受保护判定，所以 evaluate 也抛（即使调用方已经确认）
    evaluate_raises("importance-str-soft-delete", "soft_delete", broken,
                    actor="user", confirmed=True)
    evaluate_raises("importance-str-archive", "archive", broken,
                    actor="system", user_rule=True)
    # 布尔值是 int 的子类（True == 1），不抛错：不构成「≥4」，不算受保护
    add("is_protected", "importance-bool-true",
        msg={"category": "通知", "importance": True})
    add("is_protected", "importance-bool-false",
        msg={"category": "通知", "importance": False})
    # 不是破坏性动作就不经过受保护判定，非数字的 importance 不抛错
    evaluate("importance-str-label", "label", broken, actor="system")


def build_cases() -> list[dict]:
    security_test_cases()
    billing_test_cases()
    exhaustive_cases()
    confidence_cases()
    protected_cases()
    importance_type_cases()
    return CASES


def main() -> None:
    _common.run("policy", build_cases)


if __name__ == "__main__":
    main()
