"""策略引擎：动作安全分级、受保护邮件判定、置信度门槛

分级依据（对应需求文档第七条）：
  TIER_AUTO    (A) 可默认自动执行——纯标注类、可逆
  TIER_GUARDED (B) 需高置信度或用户显式规则——改变归属但仍可逆
  TIER_CONFIRM (C) 必须人工确认——破坏性或对外发送

任何动作在执行前都要过 evaluate()，返回 (allowed, tier, reason)。
"""

TIER_AUTO = "A"
TIER_GUARDED = "B"
TIER_CONFIRM = "C"

# 动作 → 安全等级
ACTION_TIER = {
    # A 类：加标签、分类、标已读、归档明确营销、加摘要、建提醒
    "label": TIER_AUTO,
    "classify": TIER_AUTO,
    "read": TIER_AUTO,
    "unread": TIER_AUTO,
    "star": TIER_AUTO,
    "digest": TIER_AUTO,
    "remind": TIER_AUTO,
    # B 类：移动文件夹、本地移除（可从回收站恢复）、自动归档订阅
    "archive": TIER_GUARDED,
    "move": TIER_GUARDED,
    "soft_delete": TIER_GUARDED,
    "quarantine": TIER_GUARDED,
    # C 类：不可逆或对外
    "purge": TIER_CONFIRM,          # 从邮箱服务器永久删除
    "empty_folder": TIER_CONFIRM,
    "send": TIER_CONFIRM,
    "reply": TIER_CONFIRM,
    "forward": TIER_CONFIRM,
    "unsubscribe": TIER_CONFIRM,
    "download_attachment": TIER_CONFIRM,
}

# 这些分类禁止任何自动破坏性动作（软删除也不行），只能人工逐封处理
PROTECTED_CATEGORIES = {"账单", "安全", "重要"}

# 允许批量清理的分类白名单——只有低价值、可再生的邮件类型
CLEANABLE_CATEGORIES = {"验证码", "订阅", "通知"}

# B 类动作的自动执行置信度下限
MIN_CONFIDENCE_GUARDED = 0.75


def is_protected(msg: dict) -> tuple[bool, str]:
    """判断邮件是否受保护，返回 (是否受保护, 原因)。
    msg 需含 category / has_attach / importance / sender_addr / replied 等字段（缺失按安全侧处理）。"""
    cat = msg.get("category") or ""
    if cat in PROTECTED_CATEGORIES:
        return True, f"分类「{cat}」属于受保护类别（交易/安全/重要），禁止自动删除"
    if msg.get("has_attach"):
        return True, "邮件含附件，可能是发票或合同，禁止自动删除"
    if (msg.get("importance") or 0) >= 4:
        return True, "AI 判定重要度 ≥4，禁止自动删除"
    if msg.get("vip"):
        return True, "发件人在 VIP 名单中"
    if msg.get("replied"):
        return True, "该会话用户已回复过"
    return False, ""


def evaluate(action: str, msg: dict, *, actor: str = "system",
             confidence: float = 1.0, user_rule: bool = False,
             confirmed: bool = False) -> tuple[bool, str, str]:
    """裁决单封邮件上的单个动作。

    actor: user | rule | ai | system
    返回 (allowed, tier, reason)。allowed=False 时 reason 说明拦截原因。
    """
    tier = ACTION_TIER.get(action, TIER_CONFIRM)  # 未知动作按最严处理

    # 用户在界面上显式点击并确认的，放行（审计仍会记录）
    if actor == "user" and confirmed:
        return True, tier, "用户已确认"

    # C 类：任何情况下都需要显式确认
    if tier == TIER_CONFIRM:
        return False, tier, f"动作「{action}」属 C 类（不可逆/对外），必须人工确认"

    # 破坏性动作先过受保护判定
    if action in ("soft_delete", "purge", "quarantine", "archive", "move"):
        protected, why = is_protected(msg)
        if protected:
            return False, tier, why

    # B 类：需要用户规则或足够置信度
    if tier == TIER_GUARDED and not user_rule:
        if actor == "ai" and confidence < MIN_CONFIDENCE_GUARDED:
            return False, tier, (f"AI 置信度 {confidence:.2f} 低于阈值 "
                                 f"{MIN_CONFIDENCE_GUARDED}，转人工审核")
        if actor == "system" and confidence < MIN_CONFIDENCE_GUARDED:
            return False, tier, "缺少用户规则且置信度不足，转人工审核"

    return True, tier, "允许"


def filter_cleanable(category: str) -> tuple[bool, str]:
    """批量清理入口的分类白名单校验"""
    if category not in CLEANABLE_CATEGORIES:
        return False, (f"分类「{category}」不在可清理白名单内"
                       f"（仅允许 {'、'.join(sorted(CLEANABLE_CATEGORIES))}），"
                       f"请在收件箱中逐封确认删除")
    return True, ""
