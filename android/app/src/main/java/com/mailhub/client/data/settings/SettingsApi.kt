package com.mailhub.client.data.settings

import com.mailhub.client.data.model.LoginResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * 设置页用到的后端接口，与 app.py 里的路由逐条对应：
 * - `GET /api/settings`：当前设置，凭据只回显「是否已设置」；
 * - `PUT /api/settings`：请求体 `SettingsBody`，所有字段可选，只提交改动的字段；
 * - `POST /api/notify/test`：按已保存的推送通道发一条测试推送。
 *
 * 用 `sessionManager.currentClient()?.callService(SettingsApi::class.java) { … }` 取实例。
 */
interface SettingsApi {

    @GET("api/settings")
    suspend fun settings(): SettingsDto

    /**
     * 保存设置。普通字段返回 `{ok:true}`；改密码时后端吊销全部会话，
     * Bearer 会话会拿到新令牌 `{ok, token, session_id, expires_ts}`，与登录返回的结构相同。
     */
    @PUT("api/settings")
    suspend fun update(@Body body: SettingsUpdate): LoginResponse

    /** 测试推送：发送失败也是 200，返回 ok=false 和 msg。 */
    @POST("api/notify/test")
    suspend fun testPush(@Body body: PushTestBody): PushTestResult
}
