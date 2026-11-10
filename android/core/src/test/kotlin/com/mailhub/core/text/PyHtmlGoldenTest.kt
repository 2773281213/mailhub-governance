package com.mailhub.core.text

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.str
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PyHtmlGoldenTest {

    @Test
    fun unescapeMatchesPython() = Golden.replay("html_unescape") { case ->
        Golden.diff(case.expected.jsonPrimitive.content, PyHtml.unescape(case.args.str("s")))
    }
}
