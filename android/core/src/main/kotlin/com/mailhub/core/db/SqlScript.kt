package com.mailhub.core.db

/**
 * 把多语句 SQL 脚本拆成单条语句，对应 Python `sqlite3.Connection.executescript()` 的拆分结果。
 *
 * `androidx.sqlite` 的 `prepare()` 一次只接受一条语句，而 `db.py` 的 `SCHEMA` 是一个包含
 * 十几条语句的脚本，所以必须自己拆。拆分规则与 SQLite 的语句边界一致：
 *
 * - `'…'`、`"…"`、`` `…` ``、`[…]` 里的分号不算语句结束（`''` 这种双写是转义）；
 * - `--` 行注释和块注释（斜杠加星号开头）里的分号也不算（块注释不嵌套，与 SQLite 一致）；
 * - `CREATE TRIGGER … BEGIN … END` 的触发器体里也有分号：见到 `TRIGGER` 之后开始计
 *   `BEGIN` / `CASE` 的嵌套层数，只有层数回到 0 之后的那个分号才是语句结束；
 * - 只有空白或注释的片段被丢弃（例如脚本末尾的注释）。
 *
 * 语句文本原样保留（只去掉首尾空白和开头的注释）：`sqlite_master` 保存的是建表语句原文，
 * 拆的时候改动语句内部的空格就会让金标对不上。去掉开头的注释不影响原文——
 * SQLite 存 `sqlite_master.sql` 时也是从第一个关键字算起。
 */
internal fun splitSqlScript(script: String): List<String> {
    val statements = ArrayList<String>()
    val current = StringBuilder()
    var index = 0
    // 触发器体（以及体内 CASE 表达式）的 BEGIN/END 嵌套层数；0 表示不在触发器体里。
    var blockDepth = 0
    var inTrigger = false

    fun flush() {
        val text = stripLeadingTrivia(current.toString()).trimEnd()
        current.setLength(0)
        if (hasSqlCode(text)) {
            statements.add(text)
        }
    }

    while (index < script.length) {
        val c = script[index]
        when {
            // 字符串字面量与带引号的标识符：分号在里面不算语句结束。
            c == '\'' || c == '"' || c == '`' -> {
                val start = index
                val quote = c
                index++
                while (index < script.length) {
                    if (script[index] == quote) {
                        // 两个连续的引号是转义，语句还没结束。
                        if (index + 1 < script.length && script[index + 1] == quote) {
                            index += 2
                            continue
                        }
                        index++
                        break
                    }
                    index++
                }
                current.append(script, start, index)
            }
            // SQL Server 风格的 [标识符]，SQLite 也接受；里面不会有 ] 的转义写法。
            c == '[' -> {
                val start = index
                index++
                while (index < script.length && script[index] != ']') {
                    index++
                }
                if (index < script.length) {
                    index++
                }
                current.append(script, start, index)
            }
            // -- 行注释
            c == '-' && index + 1 < script.length && script[index + 1] == '-' -> {
                val start = index
                index += 2
                while (index < script.length && script[index] != '\n') {
                    index++
                }
                current.append(script, start, index)
            }
            // 块注释
            c == '/' && index + 1 < script.length && script[index + 1] == '*' -> {
                val start = index
                index += 2
                while (index + 1 < script.length && !(script[index] == '*' && script[index + 1] == '/')) {
                    index++
                }
                index = minOf(index + 2, script.length)
                current.append(script, start, index)
            }
            // 关键字：只用来识别触发器体的起止。
            c.isLetter() -> {
                val start = index
                while (index < script.length && (script[index].isLetterOrDigit() || script[index] == '_')) {
                    index++
                }
                when (script.substring(start, index).uppercase()) {
                    "TRIGGER" -> inTrigger = true
                    "BEGIN" -> if (inTrigger) blockDepth++
                    "CASE" -> if (inTrigger && blockDepth > 0) blockDepth++
                    "END" -> if (inTrigger && blockDepth > 0) blockDepth--
                }
                current.append(script, start, index)
            }
            c == ';' && blockDepth == 0 -> {
                current.append(c)
                flush()
                inTrigger = false
                index++
            }
            else -> {
                current.append(c)
                index++
            }
        }
    }
    flush()
    return statements
}

/**
 * 去掉语句开头的空白与注释。SQLite 存储建表语句原文时也是从第一个关键字算起，
 * 所以去掉开头的注释不影响 `sqlite_master.sql`，只是让拆出来的语句更好读。
 */
private fun stripLeadingTrivia(text: String): String {
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            c.isWhitespace() -> index++
            c == '-' && index + 1 < text.length && text[index + 1] == '-' -> {
                while (index < text.length && text[index] != '\n') {
                    index++
                }
            }
            c == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                index += 2
                while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) {
                    index++
                }
                index = minOf(index + 2, text.length)
            }
            else -> break
        }
    }
    return text.substring(index)
}

/** [text] 里除了空白和注释之外还有没有内容（纯注释片段不送去 prepare）。 */
private fun hasSqlCode(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            c.isWhitespace() -> index++
            c == '-' && index + 1 < text.length && text[index + 1] == '-' -> {
                while (index < text.length && text[index] != '\n') {
                    index++
                }
            }
            c == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                index += 2
                while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) {
                    index++
                }
                index = minOf(index + 2, text.length)
            }
            else -> return true
        }
    }
    return false
}
