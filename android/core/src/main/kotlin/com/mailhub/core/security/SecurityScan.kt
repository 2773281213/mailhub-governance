package com.mailhub.core.security

import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.cpLen
import com.mailhub.core.text.cpSlice
import com.mailhub.core.text.pyStrip
import java.util.Locale
import java.util.regex.Pattern

/** `security_scan.scan` 的结果：风险等级与可读的判定理由。 */
data class ScanResult(
    /** 风险等级，取值见 [SecurityScan.RISK_NONE] … [SecurityScan.RISK_HIGH]。 */
    val risk: String,
    /** 判定理由，按检查顺序追加，与 Python 逐字相同。 */
    val reasons: List<String>,
)

/**
 * [SecurityScan.extractHtmlLinks] 的完整结果。
 *
 * @property links 已经提取到的链接，按出现顺序；解析中途失败时只含失败之前的部分。
 * @property complete 解析是否正常走完。false 表示中途抛了异常或 [StackOverflowError]，
 *   对应 Python `extract_html_links` 里 `except: pass` 吞掉的那种不完整解析；调用方不应当
 *   把这种结果当成「没有危险链接」。
 */
data class HtmlLinksResult(
    val links: List<Pair<String, String>>,
    val complete: Boolean,
)

/**
 * `security_scan.py` 的 Kotlin 移植：显示名欺骗、异形域名、危险附件、可疑链接与
 * SPF/DKIM/DMARC 结论的纯静态分析，不访问邮件里的任何链接、不打开附件。
 *
 * **与 Python 的刻意差异**（邮件内容由攻击者控制，这些改动防止平方级回溯与栈溢出，
 * 对应 LOCAL_MODE.md 第四节第 6 条）：
 * 1. 第 6 步的域名集合在 Python 里是 `set`，`list(hosts)[:40]` 的顺序由每个进程随机的
 *    字符串哈希决定，所以正文里有多个裸 IP、或去重后超过 40 个域名时，Python 自己每次
 *    运行的结果都可能不同（命中哪个裸 IP、40 个域名截到哪些）。这里用 `LinkedHashSet`
 *    固定为插入顺序：先正文 URL、后 HTML 链接，结果稳定；金标语料里不放依赖这种顺序的用例。
 * 2. `_DOMAIN_TEXT_RE` 的 `(?:[a-z0-9-]+\.)+` 在长链接文字上是平方级。这里先把链接文字按
 *    非域名字符切成候选，**超过 253 个字符的候选直接跳过**，再对候选做「后缀匹配」。
 *    Python 里超长候选能匹配到，这里会漏判；正常邮件里不会出现这种链接文字。
 * 3. `_ADDR_IN_NAME_RE` 匹配前把显示名截到 **1000 码点**。地址出现在 1000 码点之后的
 *    显示名在 Python 里会命中，这里不会。
 * 4. 解析 HTML 抛异常或 [StackOverflowError] 时，Python 的 `except: pass` 会当作「没有
 *    更多链接」继续；这里保留已经提取到的链接，并让 [scan] 追加中风险理由
 *    「HTML 无法完整解析」。注意 `Error` 不会被 `catch (Exception)` 捕获，所以
 *    [extractHtmlLinksResult] 里对 [StackOverflowError] 单独捕获。
 * 5. [hasConfusable] 在 `Character.getName` 没有名字时用 `Character.UnicodeScript` 兜底判断
 *    西里尔 / 希腊字母（Python 的 `unicodedata.name(ch, "")` 返回空时直接算不命中）。
 */
object SecurityScan {

    const val RISK_NONE = "none"
    const val RISK_LOW = "low"
    const val RISK_MEDIUM = "medium"
    const val RISK_HIGH = "high"

    /** 对应 `_ORDER`：等级排序，用于 [riskAtLeast]。 */
    private val ORDER = mapOf(RISK_NONE to 0, RISK_LOW to 1, RISK_MEDIUM to 2, RISK_HIGH to 3)

    /** 可执行/脚本类附件——直接判高危（对应 `DANGEROUS_EXT`）。 */
    val DANGEROUS_EXT: Set<String> = setOf(
        "exe", "scr", "com", "pif", "bat", "cmd", "msi", "jar", "vbs", "vbe",
        "js", "jse", "wsf", "wsh", "ps1", "hta", "cpl", "lnk", "reg", "apk", "dll",
    )

    /** 常被用来夹带的压缩包——中危，内容不可见（对应 `ARCHIVE_EXT`）。 */
    val ARCHIVE_EXT: Set<String> = setOf("zip", "rar", "7z", "iso", "img", "cab")

    /**
     * 常被仿冒的品牌与其官方域名后缀（对应 `BRAND_DOMAINS`）。
     * 保持 Python dict 的顺序：命中第一个被提及的品牌就停止判断。
     */
    val BRAND_DOMAINS: Map<String, List<String>> = linkedMapOf(
        "paypal" to listOf("paypal.com"),
        "apple" to listOf("apple.com", "icloud.com"),
        "google" to listOf("google.com", "gmail.com", "youtube.com"),
        "microsoft" to listOf("microsoft.com", "outlook.com", "live.com", "office.com"),
        "amazon" to listOf("amazon.com", "amazon.cn"),
        "github" to listOf("github.com"),
        "支付宝" to listOf("alipay.com", "antgroup.com"),
        "微信" to listOf("tencent.com", "qq.com", "weixin.qq.com"),
        "淘宝" to listOf("taobao.com", "alibaba.com"),
        "银行" to emptyList(), // 任何自称银行但域名非常规的都可疑
    )

    /** 对应 `_URL_RE`：正文里的裸链接，分组 1 到第一个 `/`、引号或右括号为止。 */
    private val URL_RE: Pattern =
        PyRegex.compile("https?://([^\\s/\"'>)]+)", ignoreCase = true)

    /** 对应 `_IP_HOST_RE`：整串就是一个 IPv4（可带端口）。 */
    private val IP_HOST_RE: Pattern = PyRegex.compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")

    /** 对应 `_ADDR_IN_NAME_RE`：显示名里混入的邮箱地址。 */
    private val ADDR_IN_NAME_RE: Pattern = PyRegex.compile("[\\w.+-]+@([\\w-]+\\.[\\w.-]+)")

    /** 对应 `_URGENT_RE`：高风险诱导词。 */
    private val URGENT_RE: Pattern = PyRegex.compile(
        "账户.{0,4}(冻结|异常|封停|停用)|立即(验证|确认|更新|处理)|限时.{0,6}(处理|失效)" +
            "|verify\\s+your\\s+account|account\\s+(suspended|locked|will\\s+be\\s+closed)" +
            "|urgent\\s+action|click\\s+here\\s+immediately|confirm\\s+your\\s+password",
        ignoreCase = true,
    )

    /**
     * 国家顶级域下常见的二级注册后缀（co.uk / com.cn / ne.jp / com.tw …）。
     * 不是完整的 Public Suffix List，但覆盖了邮件里最常见的情况（对应 `_SECOND_LEVEL_LABELS`）。
     */
    private val SECOND_LEVEL_LABELS: Set<String> = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "ne", "or", "go", "ltd", "plc",
        "idv", "mil", "nom", "gen", "biz", "info",
    )

    /** 严格按 Python 语义从 HTML 里收集链接时用的上限（对应 `extract_html_links` 的默认参数）。 */
    private const val DEFAULT_LINK_LIMIT = 200

    /** 正文只看前 20000 码点（对应 `scan` 里的 `body[:20000]`）。 */
    private const val BODY_LIMIT = 20000

    /** 紧迫话术只看前 3000 码点（对应 `scan` 里的 `body[:3000]`）。 */
    private const val URGENT_BODY_LIMIT = 3000

    /** HTML 正文只看前 300000 码点（对应 `(html or "")[:300000]`）。 */
    private const val HTML_LIMIT = 300000

    /** 域名集合最多检查 40 个（对应 `list(hosts)[:40]`）。 */
    private const val HOST_LIMIT = 40

    /** 链接文字里单个候选域名的长度上限（超过就跳过，防止平方级回溯）。 */
    private const val DOMAIN_CANDIDATE_LIMIT = 253

    /** `_ADDR_IN_NAME_RE` 匹配前截断的码点数（防止 `[\w.+-]+@` 在长显示名上平方级回溯）。 */
    private const val SENDER_NAME_LIMIT = 1000

    /**
     * 对应 `security_scan.extract_html_links`：从 HTML 正文里收集 `(href, 链接文字)`。
     * 只看前 300000 码点，最多返回 [limit] 条；解析失败时保留已经提取到的链接，
     * 但返回值里不带「是否完整」的标记（要区分请看 [extractHtmlLinksResult]）。
     */
    fun extractHtmlLinks(html: String?, limit: Int = DEFAULT_LINK_LIMIT): List<Pair<String, String>> =
        extractHtmlLinksResult(html, limit).links

    /**
     * 与 [extractHtmlLinks] 相同，但返回 [HtmlLinksResult]，调用方能知道解析是否完整。
     *
     * Python 的 `extract_html_links` 用 `except: pass` 吞掉异常后继续；这里保留已经提取到的
     * 链接，并把 [HtmlLinksResult.complete] 置为 false。`StackOverflowError` 是 `Error`，
     * 不会被 `catch (Exception)` 捕获，所以单独捕获——Android 上的栈比桌面 JVM 小，
     * 递归或极深的调用链可能在这里溢出。
     */
    fun extractHtmlLinksResult(html: String?, limit: Int = DEFAULT_LINK_LIMIT): HtmlLinksResult {
        val collector = HtmlLinkCollector()
        return try {
            collector.feed((html ?: "").cpSlice(0, HTML_LIMIT))
            collector.close()
            HtmlLinksResult(collector.links.take(limit), complete = true)
        } catch (_: Exception) {
            // 畸形 HTML：保留已经解析出的链接，但标记为不完整
            HtmlLinksResult(collector.links.take(limit), complete = false)
        } catch (_: StackOverflowError) {
            HtmlLinksResult(collector.links.take(limit), complete = false)
        }
    }

    /**
     * 对应 `security_scan._link_host`：`urlparse(href).hostname`，只认 http/https。
     * 不合法 URL（Python 抛 `ValueError`）与非 http(s) 协议都返回空串。
     */
    fun linkHost(href: String?): String {
        val parts = try {
            PyUrlSplit.split(href ?: "")
        } catch (_: PyValueError) {
            return ""
        }
        if (parts.scheme != "http" && parts.scheme != "https") return ""
        return parts.hostname?.lowercase(Locale.ROOT) ?: ""
    }

    /** 对应 `security_scan.parse_authentication_results`：只保留每个方法第一次出现的结论。 */
    fun parseAuthenticationResults(value: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val m = AUTH_RESULTS_RE.matcher((value ?: "").lowercase(Locale.ROOT))
        while (m.find()) {
            out.putIfAbsent(m.group(1), m.group(2))
        }
        return out
    }

    /** 对应 `security_scan._domain_of`：取 `@` 之后的域并转小写、去空白。 */
    fun domainOf(addr: String?): String {
        val text = addr ?: ""
        if ("@" !in text) return ""
        return text.substring(text.lastIndexOf('@') + 1).lowercase(Locale.ROOT).pyStrip()
    }

    /**
     * 对应 `security_scan._registrable`：取可注册域。一般取末两段；
     * ccTLD 下的 co/com/ne/ac… 二级后缀取三段。
     */
    fun registrableDomain(domain: String?): String {
        val parts = (domain ?: "").lowercase(Locale.ROOT).trimDots().split(".")
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return parts.joinToString(".")
        if (parts.size >= 3 && parts[parts.size - 1].cpLen() == 2 &&
            parts[parts.size - 2] in SECOND_LEVEL_LABELS) {
            return parts.subList(parts.size - 3, parts.size).joinToString(".")
        }
        return parts.subList(parts.size - 2, parts.size).joinToString(".")
    }

    /**
     * 对应 `security_scan._has_confusable`：域名里混入了西里尔或希腊字母。
     *
     * 与 Python 的偏差：`Character.getName` 在某些字符上没有名字（返回 null 或抛异常），
     * 此时用 `Character.UnicodeScript` 兜底判断 CYRILLIC / GREEK。Python 的
     * `unicodedata.name(ch, "")` 返回空串时直接算不命中，所以这里只会更严格。
     */    fun hasConfusable(s: String?): Boolean {
        val text = s ?: ""
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) && cp > 127) {
                val name = runCatching { Character.getName(cp) }.getOrNull()
                if (name != null && ("CYRILLIC" in name || "GREEK" in name)) return true
                if (name.isNullOrEmpty()) {
                    val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull()
                    if (script == Character.UnicodeScript.CYRILLIC ||
                        script == Character.UnicodeScript.GREEK) {
                        return true
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * 对应 `security_scan.risk_at_least`：等级不低于阈值。未知等级按 0（`none`）算。
     */
    fun riskAtLeast(risk: String?, threshold: String?): Boolean =
        (ORDER[risk] ?: 0) >= (ORDER[threshold] ?: 0)

    /**
     * 对应 `security_scan.scan`：返回风险等级与判定理由，纯离线判定，不发起任何网络请求。
     *
     * @param html 邮件的 HTML 正文，用来检查 href 的真实目标（纯文本转换会丢掉链接）。
     * @param authResults 收件服务器写入的第一条 Authentication-Results 头（SPF/DKIM/DMARC 结论）。
     */
    fun scan(
        subject: String?,
        body: String?,
        senderName: String?,
        senderAddr: String?,
        replyTo: String? = "",
        attachments: List<String>? = null,
        html: String? = "",
        authResults: String? = "",
    ): ScanResult = scanInternal(
        subject, body, senderName, senderAddr, replyTo, attachments, html, authResults,
        linkExtractor = { extractHtmlLinksResult(it) },
    )

    /**
     * [scan] 的内部实现。[linkExtractor] 只为测试注入「解析不完整」的结果，
     * 生产路径固定用 [extractHtmlLinksResult]。
     */
    internal fun scanInternal(
        subject: String?,
        body: String?,
        senderName: String?,
        senderAddr: String?,
        replyTo: String?,
        attachments: List<String>?,
        html: String?,
        authResults: String?,
        linkExtractor: (String?) -> HtmlLinksResult,
    ): ScanResult {
        val reasons = mutableListOf<String>()
        var risk = RISK_NONE

        fun bump(level: String, why: String) {
            reasons += why
            if ((ORDER[level] ?: 0) > (ORDER[risk] ?: 0)) risk = level
        }

        val subjectText = subject ?: ""
        val bodyText = (body ?: "").cpSlice(0, BODY_LIMIT)
        val senderDomain = domainOf(senderAddr)
        val senderReg = registrableDomain(senderDomain)

        // 1) 显示名里嵌了另一个域的邮箱地址。先截到 1000 码点，防止长显示名上的平方级回溯
        val addrInName = ADDR_IN_NAME_RE.matcher((senderName ?: "").cpSlice(0, SENDER_NAME_LIMIT))
        if (addrInName.find() && senderReg.isNotEmpty()) {
            val shown = addrInName.group(1)
            if (registrableDomain(shown) != senderReg) {
                bump(RISK_HIGH, "显示名伪装成 $shown，实际发件域为 $senderDomain")
            }
        }

        // 2) 显示名冒用知名品牌但域名对不上。英文品牌按整词匹配，避免 "Pineapple" 命中 apple
        val lowName = (senderName ?: "").lowercase(Locale.ROOT)
        for ((brand, domains) in BRAND_DOMAINS) {
            val mentioned = if (brand.all { it.code < 128 }) {
                brandPattern(brand).matcher(lowName).find()
            } else {
                brand in lowName
            }
            if (mentioned && senderReg.isNotEmpty()) {
                if (domains.isNotEmpty() &&
                    domains.none { senderReg == it || senderReg.endsWith(".$it") }) {
                    bump(RISK_HIGH, "自称「$brand」但发件域 $senderDomain 不属于其官方域名")
                }
                break
            }
        }

        // 3) 域名同形异义字符
        if (hasConfusable(senderDomain)) {
            bump(RISK_HIGH, "发件域 $senderDomain 含非拉丁同形字符，疑似仿冒域名")
        }

        // 4) Reply-To 与发件域不一致
        if (!replyTo.isNullOrEmpty()) {
            val rt = registrableDomain(domainOf(replyTo))
            if (rt.isNotEmpty() && senderReg.isNotEmpty() && rt != senderReg) {
                bump(RISK_MEDIUM, "回复地址域 $rt 与发件域 $senderReg 不一致")
            }
        }

        // 5) 危险附件
        for (fileName in attachments ?: emptyList()) {
            val ext = if ("." in fileName) {
                fileName.substring(fileName.lastIndexOf('.') + 1).lowercase(Locale.ROOT)
            } else {
                ""
            }
            if (ext in DANGEROUS_EXT) {
                bump(RISK_HIGH, "含可执行附件 $fileName（不会自动打开）")
            } else if (ext in ARCHIVE_EXT) {
                bump(RISK_MEDIUM, "含压缩包附件 $fileName，内容无法预先检查")
            }
        }

        // 6) 链接指向裸 IP、同形异义域名，或链接文字显示的域名与实际目标不符。
        // 集合用插入顺序，理由见类 KDoc
        val hosts = LinkedHashSet<String>()
        val urlMatch = URL_RE.matcher(bodyText)
        while (urlMatch.find()) {
            hosts += urlMatch.group(1).lowercase(Locale.ROOT).split(":")[0]
        }
        var brandSpoof: Pair<String, String>? = null
        var plainMismatch: Pair<String, String>? = null
        val htmlLinks = linkExtractor(html ?: "")
        for ((href, linkText) in htmlLinks.links) {
            val host = linkHost(href)
            if (host.isEmpty()) continue
            hosts += host
            val shown = findShownDomain(linkText) ?: continue
            val shownReg = registrableDomain(shown)
            val targetReg = registrableDomain(host)
            if (shownReg.isEmpty() || targetReg == shownReg || targetReg == senderReg) {
                continue // 指向显示的域名，或指向发件人自己的域名（常见的点击统计跳转）
            }
            val pair = shown.lowercase(Locale.ROOT) to host
            if (brandSpoof == null && BRAND_DOMAINS.values.any { shownReg in it }) {
                brandSpoof = pair
            } else if (plainMismatch == null) {
                plainMismatch = pair
            }
        }
        if (!htmlLinks.complete) {
            // 与 Python 的刻意差异：解析不完整时不放行，按中风险处理
            bump(RISK_MEDIUM, "HTML 无法完整解析")
        }
        val firstHosts = hosts.take(HOST_LIMIT)
        if (brandSpoof != null) {
            bump(RISK_HIGH, "链接文字显示为知名品牌域名 ${brandSpoof.first}，实际指向 ${brandSpoof.second}")
        } else if (plainMismatch != null) {
            // 营销邮件普遍经第三方点击统计域名跳转，单独出现只作提示，不强制归为可疑
            bump(RISK_LOW, "链接文字显示为 ${plainMismatch.first}，实际指向 ${plainMismatch.second}")
        }
        for (host in firstHosts) {
            if (IP_HOST_RE.matcher(host).lookingAt()) {
                bump(RISK_HIGH, "正文含指向裸 IP 的链接 $host")
                break
            }
        }
        if (hasConfusable(firstHosts.joinToString(" "))) {
            bump(RISK_MEDIUM, "正文链接域名含同形异义字符")
        }

        // 7) 紧迫性话术 + 外链，典型钓鱼组合
        if (URGENT_RE.matcher(subjectText + "\n" + bodyText.cpSlice(0, URGENT_BODY_LIMIT)).find()) {
            if (hosts.isNotEmpty()) {
                bump(RISK_MEDIUM, "含账户风险类紧迫话术且带外部链接，符合钓鱼特征")
            } else {
                bump(RISK_LOW, "含账户风险类紧迫话术")
            }
        }

        // 8) 收件服务器的 SPF/DKIM/DMARC 结论
        val auth = parseAuthenticationResults(authResults ?: "")
        if (auth["dmarc"] == "fail") {
            bump(RISK_MEDIUM, "发件域 DMARC 验证失败，发件人可能被冒充")
        } else if (auth["spf"] == "fail" || auth["spf"] == "softfail") {
            // Python: auth.get("dkim") in (None, "none", "fail")
            val dkim = auth["dkim"]
            if (dkim == null || dkim == "none" || dkim == "fail") {
                bump(RISK_MEDIUM, "SPF 验证失败且没有有效 DKIM 签名")
            }
        }

        return ScanResult(risk, reasons)
    }

    /** `Authentication-Results` 里的 `spf|dkim|dmarc = 结论`。 */
    private val AUTH_RESULTS_RE: Pattern = PyRegex.compile("\\b(spf|dkim|dmarc)\\s*=\\s*([a-z]+)")

    /** 英文品牌在显示名里的整词匹配模式（动态拼接的部分走 [PyRegex.escape]）。 */
    private val brandPatterns = HashMap<String, Pattern>()

    private fun brandPattern(brand: String): Pattern = synchronized(brandPatterns) {
        brandPatterns.getOrPut(brand) {
            PyRegex.compile("(?<![a-z0-9])" + PyRegex.escape(brand) + "(?![a-z0-9])")
        }
    }

    /**
     * 对应 `_DOMAIN_TEXT_RE.search(text)` 的分组 1，但按候选切分后再匹配：
     * 先取一段连续的域名字符（`[a-z0-9-.]`），候选必须被 `/`、`:`、`?`、`#` 或字符串结尾
     * 收住，长度不超过 [DOMAIN_CANDIDATE_LIMIT]；再要求候选的后缀能匹配
     * `(?:[a-z0-9-]+\.)+[a-z]{2,}\Z`（见 [domainSuffix]）。这样每个字符最多看常数次，
     * 既不会在 `a.a.a…` 上回溯爆炸，也不需要带嵌套量词的正则。
     *
     * 与 Python 的偏差：超过 253 个字符的候选直接跳过（Python 会整段匹配后取末两段，
     * 结果等价于「没有可注册域」，这里直接当没有匹配）。
     */
    private fun findShownDomain(text: String): String? {
        val n = text.length
        var i = 0
        while (i < n) {
            if (!isDomainChar(text[i])) {
                i++
                continue
            }
            var j = i + 1
            while (j < n && isDomainChar(text[j])) j++
            val delimited = j == n || text[j] == '/' || text[j] == ':' ||
                text[j] == '?' || text[j] == '#'
            if (delimited && j - i <= DOMAIN_CANDIDATE_LIMIT) {
                val shown = domainSuffix(text.substring(i, j))
                if (shown != null) return shown
            }
            i = j
        }
        return null
    }

    /**
     * `(?:[a-z0-9-]+\.)+[a-z]{2,}\Z` 在候选上的左端最短匹配，返回分组 1。
     *
     * 候选只含域名字符，所以只需要：跳过所有空标签（开头的点或连续的点）、至少有一个点、
     * 最后一个标签全是字母且长度 >= 2。匹配必须到候选末尾，因此起点的最左选择就是
     * 「最后一个空标签之后」。手写判断是线性的，不会回溯。
     */
    private fun domainSuffix(candidate: String): String? {
        val n = candidate.length
        var start = 0
        for (p in 0 until n) {
            if (candidate[p] == '.' && (p == 0 || candidate[p - 1] == '.')) start = p + 1
        }
        if (start >= n) return null
        val lastDot = candidate.lastIndexOf('.')
        if (lastDot <= start) return null
        val tail = candidate.substring(lastDot + 1)
        if (tail.length < 2) return null
        for (k in tail.indices) {
            if (!isDomainLetter(tail[k])) return null
        }
        return candidate.substring(start)
    }

    /**
     * `[a-z0-9-]` 的字母部分在 `re.I` 下的字符集。除 ASCII 外把 Python 文档列出的四个特殊
     * 折叠字符（`İ` U+0130、`ı` U+0131、`ſ` U+017F、开尔文符号 U+212A）也算作字母，
     * 否则候选切分与后缀判断会和 Python 的正则不一致。
     */
    private fun isDomainLetter(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' ||
            c == '\u0130' || c == '\u0131' || c == '\u017F' || c == '\u212A'

    /** `[a-z0-9-.]` 在 `re.I` 下的字符集：字母、数字、连字符、点。 */
    private fun isDomainChar(c: Char): Boolean =
        isDomainLetter(c) || c in '0'..'9' || c == '-' || c == '.'

    /** Python 的 `s.strip(".")`：只去掉两端的点。 */
    private fun String.trimDots(): String {
        var start = 0
        var end = length
        while (start < end && this[start] == '.') start++
        while (end > start && this[end - 1] == '.') end--
        return substring(start, end)
    }
}
