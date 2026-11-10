package com.mailhub.core.rules

import com.mailhub.core.text.Py

/**
 * 受保护判定结果，对应 Python `policy.is_protected` 返回的 `(bool, str)`。
 */
data class ProtectionResult(
    val protected: Boolean,
    val reason: String,
)

/** 动作裁决结果，对应 Python `policy.evaluate` 返回的 `(allowed, tier, reason)`。 */
data class Decision(
    val allowed: Boolean,
    val tier: String,
    val reason: String,
)

/** 批量清理白名单校验结果，对应 Python `policy.filter_cleanable` 返回的 `(bool, str)`。 */
data class CleanableResult(
    val allowed: Boolean,
    val reason: String,
)

/**
 * 策略引擎：动作安全分级、受保护邮件判定、置信度门槛。
 *
 * 逐函数移植 Python `policy.py`。分级依据：
 * - [TIER_AUTO] (A) 可默认自动执行——纯标注类、可逆；
 * - [TIER_GUARDED] (B) 需高置信度或用户显式规则——改变归属但仍可逆；
 * - [TIER_CONFIRM] (C) 必须人工确认——破坏性或对外发送。
 *
 * 任何动作在执行前都要过 [evaluate]。拦截原因的文字与原实现逐字相同，
 * 金标语料见 `android/core/src/test/resources/golden/policy.json`。
 */
object Policy {

    const val TIER_AUTO: String = "A"
    const val TIER_GUARDED: String = "B"
    const val TIER_CONFIRM: String = "C"

    /** B 类动作的自动执行置信度下限。对应 Python `MIN_CONFIDENCE_GUARDED`。 */
    const val MIN_CONFIDENCE_GUARDED: Double = 0.75

    /** 动作 → 安全等级。对应 Python `ACTION_TIER`。 */
    val ACTION_TIER: Map<String, String> = linkedMapOf(
        // A 类：加标签、分类、标已读、归档明确营销、加摘要、建提醒
        "label" to TIER_AUTO,
        "classify" to TIER_AUTO,
        "read" to TIER_AUTO,
        "unread" to TIER_AUTO,
        "star" to TIER_AUTO,
        "digest" to TIER_AUTO,
        "remind" to TIER_AUTO,
        // B 类：移动文件夹、本地移除（可从回收站恢复）、自动归档订阅
        "archive" to TIER_GUARDED,
        "move" to TIER_GUARDED,
        "soft_delete" to TIER_GUARDED,
        "quarantine" to TIER_GUARDED,
        // C 类：不可逆或对外
        "purge" to TIER_CONFIRM,
        "empty_folder" to TIER_CONFIRM,
        "send" to TIER_CONFIRM,
        "reply" to TIER_CONFIRM,
        "forward" to TIER_CONFIRM,
        "unsubscribe" to TIER_CONFIRM,
        "download_attachment" to TIER_CONFIRM,
    )

    /** 这些分类禁止任何自动破坏性动作（软删除也不行），只能人工逐封处理。 */
    val PROTECTED_CATEGORIES: Set<String> = linkedSetOf("账单", "安全", "重要")

    /** 会让邮件离开收件箱的动作；执行前一律先过受保护判定。 */
    val DESTRUCTIVE_ACTIONS: Set<String> = linkedSetOf("soft_delete", "purge", "quarantine", "archive", "move")

    /** 允许批量清理的分类白名单——只有低价值、可再生的邮件类型。 */
    val CLEANABLE_CATEGORIES: Set<String> = linkedSetOf("验证码", "订阅", "通知")

    /**
     * 对应 Python `policy.is_protected`：判断邮件是否受保护，返回 (是否受保护, 原因)。
     * msg 需含 category / has_attach / importance / sender_addr / replied 等字段（缺失按安全侧处理）。
     */
    fun isProtected(msg: Map<String, Any?>): ProtectionResult {
        val cat = (msg["category"] as? String).orEmpty()
        // 一般账单（到账、零金额、例行流水）不按分类保护；带附件、重要度高的仍受保护
        val minorBill = cat == "账单" && msg["bill_tier"] == "一般"
        if (cat in PROTECTED_CATEGORIES && !minorBill) {
            return ProtectionResult(true, "分类「$cat」属于受保护类别（交易/安全/重要），禁止自动删除")
        }
        if (Py.truthy(msg["has_attach"])) {
            return ProtectionResult(true, "邮件含附件，可能是发票或合同，禁止自动删除")
        }
        if (importanceOrZero(msg["importance"]) >= 4) {
            return ProtectionResult(true, "AI 判定重要度 ≥4，禁止自动删除")
        }
        if (Py.truthy(msg["vip"])) {
            return ProtectionResult(true, "发件人在 VIP 名单中")
        }
        if (Py.truthy(msg["replied"])) {
            return ProtectionResult(true, "该会话用户已回复过")
        }
        return ProtectionResult(false, "")
    }

    /**
     * 对应 Python `policy.evaluate`：裁决单封邮件上的单个动作。
     *
     * @param actor user | rule | ai | system
     * @param confirmed 用户在界面上确认过（actor=user），或用户在设置里显式开启的长期规则
     *   （actor=system 且 userRule=true）。它只解除 C 类动作的确认门槛，不会跳过受保护判定。
     * @param overrideProtection 用户对这一封受保护邮件做了单独的二次确认。只对
     *   actor=user 且 confirmed=true 生效；调用方负责保证它只用于单封操作。
     */
    fun evaluate(
        action: String,
        msg: Map<String, Any?>,
        actor: String = "system",
        confidence: Double = 1.0,
        userRule: Boolean = false,
        confirmed: Boolean = false,
        overrideProtection: Boolean = false,
    ): Decision {
        val tier = ACTION_TIER[action] ?: TIER_CONFIRM // 未知动作按最严处理

        // 受保护判定永远先于「已确认」：批量确认、自动清理都不能带走受保护邮件
        if (action in DESTRUCTIVE_ACTIONS) {
            val protection = isProtected(msg)
            if (protection.protected && !(actor == "user" && confirmed && overrideProtection)) {
                return Decision(false, tier, protection.reason)
            }
        }

        // 用户在界面上显式确认，或用户开启的长期规则：放行（审计仍会记录）
        if (confirmed && actor == "user") {
            return Decision(true, tier, "用户已确认")
        }
        if (confirmed && actor == "system" && userRule) {
            return Decision(true, tier, "用户在设置中开启的长期规则")
        }

        // C 类：任何情况下都需要显式确认
        if (tier == TIER_CONFIRM) {
            return Decision(false, tier, "动作「$action」属 C 类（不可逆/对外），必须人工确认")
        }

        // B 类：需要用户规则或足够置信度
        if (tier == TIER_GUARDED && !userRule) {
            if (actor == "ai" && confidence < MIN_CONFIDENCE_GUARDED) {
                // 原实现是 f"{confidence:.2f}" 和 f"{MIN_CONFIDENCE_GUARDED}"；
                // str(0.75) 与 Kotlin 的 Double.toString(0.75) 都是 "0.75"。
                return Decision(
                    false, tier,
                    "AI 置信度 ${Py.formatFixed(confidence, 2)} 低于阈值 $MIN_CONFIDENCE_GUARDED，转人工审核",
                )
            }
            if (actor == "system" && confidence < MIN_CONFIDENCE_GUARDED) {
                return Decision(false, tier, "缺少用户规则且置信度不足，转人工审核")
            }
        }

        return Decision(true, tier, "允许")
    }

    /** 对应 Python `policy.filter_cleanable`：批量清理入口的分类白名单校验。 */
    fun filterCleanable(category: String): CleanableResult {
        if (category !in CLEANABLE_CATEGORIES) {
            // Python 是 "、".join(sorted(CLEANABLE_CATEGORIES))，三个分类都在 BMP 内，
            // Kotlin 的字符串自然序（按 UTF-16 码元）与 Python 的码点序一致
            val allowed = CLEANABLE_CATEGORIES.sorted().joinToString("、")
            return CleanableResult(
                false,
                "分类「$category」不在可清理白名单内（仅允许 $allowed），请在收件箱中逐封确认删除",
            )
        }
        return CleanableResult(true, "")
    }

    /**
     * 对应 Python `(msg.get("importance") or 0) >= 4`。
     *
     * 取值类型必须与 Python 一致：`None` 按 0 处理，`Number` 按其数值，`Boolean` 按 Python
     * 里 `True == 1` / `False == 0` 处理。值是「真值但不是数字」（例如字符串 `"5"`）时，
     * Python 的 `>= 4` 会抛 **TypeError**，异常一路冒到调用方，裁决拿不到结果、删除动作因此被阻断
     * ——这是安全侧的结果。Kotlin 没有隐式比较，所以这里对应地抛 [IllegalArgumentException]：
     * 调用方同样拿不到「允许」的结论，而不是把非数字当成 0 悄悄放行。
     */
    private fun importanceOrZero(value: Any?): Double = when (value) {
        null -> 0.0
        is Boolean -> if (value) 1.0 else 0.0
        is Number -> value.toDouble()
        else -> throw IllegalArgumentException(
            "importance 必须是数字，实际是 ${value::class.simpleName}：「$value」。" +
                "对应 Python 的 TypeError，删除动作被阻断",
        )
    }
}
