package com.mailhub.client.work

import com.mailhub.client.data.model.MessageSummary
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 增量接口 `GET /api/changes`（app.py 的 `_changes_sync`）：
 * - `after_id = 0`：只返回当前水位 `cursor`，不补发历史邮件；
 * - `after_id` 比服务器的最大 id 还大（服务器恢复了旧备份）：`reset = true`；
 * - 其余情况返回 id 在游标之后、已经治理完的邮件，分类是治理后的最终分类。
 */
interface ChangesApi {

    @GET("api/changes")
    suspend fun changes(
        @Query("after_id") afterId: Long,
        @Query("limit") limit: Int,
    ): ChangesPage
}

/** 条目的字段是邮件列表的子集，直接复用 [MessageSummary]。 */
@Serializable
data class ChangesPage(
    val items: List<MessageSummary> = emptyList(),
    val cursor: Long = 0,
    val reset: Boolean = false,
)
