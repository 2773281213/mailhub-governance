package com.mailhub.client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: Int = 0,
    val name: String = "",
    val provider: String = "",
    val email: String = "",
    val enabled: Boolean = true,
    @SerialName("last_sync") val lastSync: Double = 0.0,
    @SerialName("last_error") val lastError: String = "",
    val total: Int = 0,
    val unread: Int = 0,
    @SerialName("sync_state") val syncState: SyncState = SyncState(),
)

@Serializable
data class SyncState(
    val state: String = "idle",
    val msg: String = "",
    val ts: Double = 0.0,
    @SerialName("new") val newCount: Int = 0,
)
