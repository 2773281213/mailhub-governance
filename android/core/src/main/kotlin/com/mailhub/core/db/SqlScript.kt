package com.mailhub.core.db

/**
 * 把多语句 SQL 脚本拆成单条语句，对应 Python `sqlite3.Connection.executescript()` 的拆分结果。
 *
 * `androidx.sqlite` 的 `prepare()` 一次只接受一条语句，而 `db.py` 的 `SCHEMA` 是一个包含
 * 十几条语句的脚本，所以必须自己拆。拆分规则与 SQLite 的语句边界一致：
 *
 * - `'…'`、`"…"`、`` `…` ``、`[…]` 里的分号不算语句结束（`''` 这种双写是转义）；
 * - `--` 行注释和块注释（斜杠加星号开头）里的分号也不算（块注释不嵌套，与 SQLite 一致）；
 * - `CREATE TRIGGER … BEGIN … END` 的触发器体里也有分号：只有语句开头是
 *   `CREATE [TEMP|TEMPORARY] TRIGGER` 才进入触发器模式，进入之后计 `BEGIN` / `CASE` 的
 *   嵌套层数，只有层数回到 0 之后的那个分号才是语句结束。**不能见到 `TRIGGER` 就进触发器
 *   模式**：`governance_runs` 有一列就叫 `trigger`，`SELECT trigger, begin FROM t;` 之后
 *   的分号必须照常断句；
 * - 标识符可以以 `_`、`$`、`:`、`@` 开头（后三个是 TCL 风格的参数前缀），所以 `_end`、
 *   `:begin` 这类整段是一个标识符，`_end` 不能被当成 `END`；
 * - `.` 后面是限定列名（`new.end`），那一整段当标识符看，不是关键字；
 * - 只有空白或注释的片段被丢弃（例如脚本末尾的注释）。
 *
 * **已知限制：** 触发器体里如果把 `begin`、`end` 当作**未加引号的列名**（例如
 * `INSERT INTO t(end) VALUES(new.begin)`），它们仍会被当成关键字，把 `BEGIN` / `END`
 * 的层数算乱；SQLite 接受这种写法，但 `db.py` 现有的脚本里没有。真遇到时给列名加引号
 * （`"end"`）即可。列名 `trigger` 不受影响，因为是否进入触发器体只看语句开头的关键字。
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
    // 当前语句开头的关键字（只看前 3 个），用来判断是不是 CREATE [TEMP|TEMPORARY] TRIGGER。
    val leadingKeywords = ArrayList<String>(LEADING_KEYWORD_LIMIT)
    // 上一个非空白字符：紧跟在 `.` 后面的标识符是限定列名（`new.end`），不是关键字。
    var previousSignificant = ' '

    fun flush() {
        val text = stripLeadingTrivia(current.toString()).trimEnd()
        current.setLength(0)
        leadingKeywords.clear()
        previousSignificant = ' '
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
                previousSignificant = script[index - 1]
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
                previousSignificant = script[index - 1]
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
            // 标识符与关键字（关键字只用来识别触发器体的起止）。
            isIdentifierStart(c) -> {
                val start = index
                index++
                while (index < script.length && isIdentifierPart(script[index])) {
                    index++
                }
                val word = script.substring(start, index)
                val keyword = word.uppercase()
                // `.` 后面是限定列名：`new.end` 里的 end 不是 END。
                if (previousSignificant != '.') {
                    if (leadingKeywords.size < LEADING_KEYWORD_LIMIT) {
                        leadingKeywords.add(keyword)
                        if (startsCreateTrigger(leadingKeywords)) {
                            inTrigger = true
                        }
                    }
                    when (keyword) {
                        "BEGIN" -> if (inTrigger) blockDepth++
                        "CASE" -> if (inTrigger && blockDepth > 0) blockDepth++
                        "END" -> if (inTrigger && blockDepth > 0) blockDepth--
                    }
                }
                current.append(word)
                previousSignificant = script[index - 1]
            }
            c == ';' && blockDepth == 0 -> {
                current.append(c)
                flush()
                inTrigger = false
                index++
            }
            else -> {
                current.append(c)
                if (!c.isWhitespace()) {
                    previousSignificant = c
                }
                index++
            }
        }
    }
    flush()
    return statements
}

/** 触发器体的识别只看语句最前面的这几个关键字。 */
private const val LEADING_KEYWORD_LIMIT = 3

/** 语句开头是 `CREATE [TEMP|TEMPORARY] TRIGGER` 时才进触发器模式（语句还没走完）。 */
private fun startsCreateTrigger(keywords: List<String>): Boolean = when (keywords.size) {
    2 -> keywords[0] == "CREATE" && keywords[1] == "TRIGGER"
    3 -> keywords[0] == "CREATE" &&
        (keywords[1] == "TEMP" || keywords[1] == "TEMPORARY") &&
        keywords[2] == "TRIGGER"
    else -> false
}

/** 标识符的首字符：字母，或者 `_`、`$`、`:`、`@` 这些 TCL 风格的参数前缀。 */
private fun isIdentifierStart(c: Char): Boolean =
    c.isLetter() || c == '_' || c == '$' || c == ':' || c == '@'

private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || isIdentifierStart(c)

/**
 * 从 [from] 开始跳过空白与注释，返回第一个既不是空白也不是注释的字符下标；
 * 一直跳到末尾时返回 `text.length`。
 *
 * 拆分脚本时去开头的注释、判断片段里还有没有 SQL、取语句的第一个关键字、统计占位符，
 * 用的是同一套「什么是注释」的规则；实现只留这一份，四处复用。
 */
internal fun skipTrivia(text: String, from: Int): Int {
    var index = from
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            index++
            continue
        }
        val commentEnd = commentEnd(text, index)
        if (commentEnd >= 0) {
            index = commentEnd
            continue
        }
        return index
    }
    return index
}

/** [index] 处是注释开头时返回注释之后的下标，否则返回 -1。 */
private fun commentEnd(text: String, index: Int): Int {
    if (index + 1 >= text.length) {
        return -1
    }
    if (text[index] == '-' && text[index + 1] == '-') {
        var end = index + 2
        while (end < text.length && text[end] != '\n') {
            end++
        }
        return end
    }
    if (text[index] == '/' && text[index + 1] == '*') {
        var end = index + 2
        while (end + 1 < text.length && !(text[end] == '*' && text[end + 1] == '/')) {
            end++
        }
        return minOf(end + 2, text.length)
    }
    return -1
}

/** [index] 处是引号时返回配对引号之后的下标（双写是转义）；不是引号时返回 -1。 */
private fun quotedEnd(text: String, index: Int): Int {
    val quote = text[index]
    if (quote != '\'' && quote != '"' && quote != '`') {
        return -1
    }
    var end = index + 1
    while (end < text.length) {
        if (text[end] == quote) {
            if (end + 1 < text.length && text[end + 1] == quote) {
                end += 2
                continue
            }
            return end + 1
        }
        end++
    }
    return text.length
}

/** [index] 处是 `[` 时返回 `]` 之后的下标；没有闭合时返回 `text.length`。 */
private fun bracketEnd(text: String, index: Int): Int {
    var end = index + 1
    while (end < text.length && text[end] != ']') {
        end++
    }
    return if (end < text.length) end + 1 else text.length
}

/**
 * 统计 SQL 里字符串、带引号的标识符与注释之外的 `?` 占位符个数。
 *
 * 只数匿名 `?`（本类也只支持这种）；`?123` 这种带编号的占位符按一个算，调用方不要用。
 * 用来在绑定之前核对参数个数：Python 的 `sqlite3` 少传参数会抛 `ProgrammingError`，
 * 而 `androidx.sqlite` 会把没绑的参数静默当成 NULL，移植 24 个占位符的 INSERT 时漏一个
 * 就会悄悄写进 NULL。
 */
internal fun countPlaceholders(sql: String): Int {
    var count = 0
    var index = 0
    while (index < sql.length) {
        val c = sql[index]
        when {
            c == '?' -> {
                count++
                index++
            }
            c == '[' -> index = bracketEnd(sql, index)
            else -> {
                val quoted = quotedEnd(sql, index)
                if (quoted >= 0) {
                    index = quoted
                    continue
                }
                val comment = commentEnd(sql, index)
                if (comment >= 0) {
                    index = comment
                    continue
                }
                index++
            }
        }
    }
    return count
}

/**
 * 去掉语句开头的空白与注释。SQLite 存储建表语句原文时也是从第一个关键字算起，
 * 所以去掉开头的注释不影响 `sqlite_master.sql`，只是让拆出来的语句更好读。
 */
private fun stripLeadingTrivia(text: String): String = text.substring(skipTrivia(text, 0))

/**
 * [text] 里除了空白、注释和空语句的分号之外还有没有内容（纯注释或 `;;` 片段不送去 prepare）。
 *
 * 只含分号的片段是 `executescript` 里的空语句，Python 会直接跳过；这里也必须丢掉，
 * 否则 `executeScript(";COMMIT;")` 会先拿 `;` 去 prepare 而报 SQLite 错误，
 * 轮不到对 `COMMIT` 的事务控制检查。
 */
private fun hasSqlCode(text: String): Boolean {
    var index = skipTrivia(text, 0)
    while (index < text.length && text[index] == ';') {
        index = skipTrivia(text, index + 1)
    }
    return index < text.length
}
