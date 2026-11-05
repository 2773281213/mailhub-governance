package com.mailhub.client.data.security

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 安全中心用到的后端接口，与 app.py 的路由逐条对应：
 * 风险观察 `GET /api/review`、回收站 `GET /api/trash` 与 `POST /api/trash/restore`、
 * 审计 `GET /api/audit` 与 `POST /api/audit/{id}/undo`、
 * 已登录设备 `GET /api/sessions`、`DELETE /api/sessions/{id}`、`POST /api/sessions/revoke-all`。
 */
interface SecurityApi {

    @GET("api/review")
    suspend fun review(@Query("limit") limit: Int): List<ReviewItem>

    @GET("api/trash")
    suspend fun trash(@Query("limit") limit: Int): List<TrashItem>

    @POST("api/trash/restore")
    suspend fun restore(@Body body: RestoreRequest): RestoreResult

    @GET("api/audit")
    suspend fun audit(@Query("limit") limit: Int, @Query("offset") offset: Int): AuditPage

    /** 不可撤销、已撤销过的记录返回 400，detail 是中文说明。 */
    @POST("api/audit/{id}/undo")
    suspend fun undo(@Path("id") id: Long): UndoResult

    @GET("api/sessions")
    suspend fun sessions(): SessionsPage

    @DELETE("api/sessions/{id}")
    suspend fun revoke(@Path("id") id: String): RevokeResult

    @POST("api/sessions/revoke-all")
    suspend fun revokeAll(): RevokeAllResult
}
