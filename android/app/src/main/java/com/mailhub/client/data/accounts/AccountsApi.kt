package com.mailhub.client.data.accounts

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OkResponse
import com.mailhub.client.data.model.SyncState
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * 账户管理用到的后端接口。账户功能在自己的目录里声明接口，
 * 用 `sessionManager.currentClient()?.callService(AccountsApi::class.java) { … }` 取实例。
 *
 * 路由与 app.py / oauth_auth.py 里的定义逐条对应：
 * - `GET/POST /api/accounts`、`PUT/DELETE /api/accounts/{id}`
 * - `POST /api/accounts/{id}/sync`、`POST /api/accounts/{id}/test`
 * - `POST /api/sync-all`、`GET /api/status`
 * - `GET /api/oauth/providers`、`POST /api/oauth/start`、`GET /api/oauth/transactions/{id}`
 * - `POST /api/oauth/outlook/device/start`、`POST /api/oauth/outlook/device/poll`
 */
interface AccountsApi {

    /** 账户列表，后端按 id 升序返回，页面上的 A1 A2 就是这个顺序。 */
    @GET("api/accounts")
    suspend fun accounts(): List<Account>

    /** 新建授权码 / 密码账户；后端会先探测 IMAP 连接，可能要等十几秒。 */
    @POST("api/accounts")
    suspend fun createAccount(@Body body: AccountBody): AccountCreated

    /** 修改账户；OAuth 账户不允许直接改服务商和邮箱地址。 */
    @PUT("api/accounts/{id}")
    suspend fun updateAccount(
        @Path("id") id: Int,
        @Body body: AccountBody,
    ): OkResponse

    /** 删除账户，本地邮件一并删除。 */
    @DELETE("api/accounts/{id}")
    suspend fun deleteAccount(@Path("id") id: Int): OkResponse

    /** 立即同步单个账户。 */
    @POST("api/accounts/{id}/sync")
    suspend fun syncAccount(@Path("id") id: Int): OkResponse

    /** 测试账户连接；连接失败时也是 200，返回 ok=false 和 msg。 */
    @POST("api/accounts/{id}/test")
    suspend fun testAccount(@Path("id") id: Int): ConnectionTest

    /** 立即同步全部账户。 */
    @POST("api/sync-all")
    suspend fun syncAll(): OkResponse

    /** 各账户的同步状态，键是账户 id 的字符串形式，值是 {state,msg,ts,new}。 */
    @GET("api/status")
    suspend fun syncStatus(): Map<String, SyncState>

    /** 服务商列表与当前部署可用的认证方式。 */
    @GET("api/oauth/providers")
    suspend fun providers(): OAuthProvidersResponse

    /** 发起浏览器 OAuth；client 固定传 android，回调页才会给出 mailhub:// 返回入口。 */
    @POST("api/oauth/start")
    suspend fun oauthStart(@Body body: OAuthStartBody): OAuthStartResponse

    /** 查询浏览器 OAuth 事务状态；只返回状态和账户 id，不含任何凭据。 */
    @GET("api/oauth/transactions/{id}")
    suspend fun oauthTransaction(@Path("id") id: String): OAuthTransaction

    /** 发起 Outlook 设备码登录。 */
    @POST("api/oauth/outlook/device/start")
    suspend fun deviceStart(@Body body: OAuthStartBody): DeviceCodeStart

    /** 轮询设备码登录结果；等待中是 {pending:true,interval}，成功是 {ok:true,id}。 */
    @POST("api/oauth/outlook/device/poll")
    suspend fun devicePoll(@Body body: DevicePollBody): DeviceCodePoll
}
