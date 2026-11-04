package com.mailhub.client.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 同一个文件在进程里只能有一个 DataStore：用委托属性保证单例。 */
private val Context.changesDataStore: DataStore<Preferences> by preferencesDataStore(name = "mailhub_changes")

/**
 * `/api/changes` 的游标，按服务器地址分开存：换了服务器，各自的进度互不干扰。
 * 存在独立的 `mailhub_changes` 文件里，不和会话混在一起。
 */
class ChangesCursorStore(private val dataStore: DataStore<Preferences>) {

    /** 还没记过游标时返回 null。 */
    suspend fun get(origin: String): Long? = dataStore.data.first()[keyOf(origin)]

    suspend fun set(origin: String, cursor: Long) {
        dataStore.edit { it[keyOf(origin)] = cursor }
    }

    private fun keyOf(origin: String) = longPreferencesKey("cursor|$origin")

    companion object {

        fun of(context: Context): ChangesCursorStore =
            ChangesCursorStore(context.applicationContext.changesDataStore)
    }
}

/**
 * 服务器地址归一成 `scheme://host:port`，作为游标的键。
 * 同一台服务器写成带不带结尾斜杠、带不带默认端口，得到的都是同一个键；地址无效时返回 null。
 */
fun originOf(serverUrl: String): String? {
    val url = serverUrl.toHttpUrlOrNull() ?: return null
    return "${url.scheme}://${url.host}:${url.port}"
}
