package com.mailhub.client.data.net

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.GovernanceRunRequest
import com.mailhub.client.data.model.GovernanceStatus
import com.mailhub.client.data.model.LoginRequest
import com.mailhub.client.data.model.LoginResponse
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.data.model.MessagePage
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.model.OkResponse
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.data.model.Overview
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 一期需要的全部接口。翻页只传 cursor（不透明 token），不自己拼 before_ts。 */
interface MailHubApi {

    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("api/logout")
    suspend fun logout(): OkResponse

    @GET("api/meta")
    suspend fun meta(): Meta

    @GET("api/overview")
    suspend fun overview(): Overview

    @GET("api/governance/status")
    suspend fun governanceStatus(): GovernanceStatus

    @POST("api/governance/run")
    suspend fun governanceRun(@Body body: GovernanceRunRequest): GovernanceStatus

    @GET("api/accounts")
    suspend fun accounts(): List<Account>

    @POST("api/sync-all")
    suspend fun syncAll(): OkResponse

    @GET("api/messages")
    suspend fun messages(
        @Query("category") category: String? = null,
        @Query("account") account: Int? = null,
        @Query("unread") unread: Int? = null,
        @Query("q") q: String? = null,
        @Query("bill_tier") billTier: String? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("cursor") cursor: String? = null,
    ): MessagePage

    @GET("api/messages/{id}")
    suspend fun message(
        @Path("id") id: Long,
        @Query("mark_read") markRead: Boolean = true,
    ): MessageDetail

    @POST("api/messages/batch")
    suspend fun batch(@Body body: BatchRequest): BatchResult

    @GET("api/otp")
    suspend fun otp(@Query("limit") limit: Int? = null): List<OtpItem>
}
