package com.mailhub.core.rules

import com.mailhub.core.db.Db

/**
 * 读取用户自定义规则，对应 Python `rules.load_custom_rules(conn)`。
 *
 * 只取 `enabled=1` 的规则，按 `priority ASC, id ASC` 排序（同名优先级时保持创建顺序），
 * 与服务器完全一致。返回的顺序就是 [Rules.classifyDetailed] 的匹配顺序：
 * 第一条命中的规则生效。
 */
fun loadCustomRules(db: Db): List<CustomRule> =
    db.query(
        "SELECT field, pattern, category FROM rules WHERE enabled=1 ORDER BY priority ASC, id ASC",
    ).map { row ->
        CustomRule(
            field = row.str("field") ?: "",
            pattern = row.str("pattern") ?: "",
            category = row.str("category") ?: "",
        )
    }
