package com.awesome.dhs.tools.downloader.utils

import com.awesome.dhs.tools.downloader.core.InMemoryCookieJar
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class SerializableCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean, // 虽然 Builder 很难完美还原这个状态，但存下来是个好习惯
) {
    // 伴生对象提供转换方法
    companion object {
        // OkHttp Cookie -> SerializableCookie
        fun from(cookie: Cookie): SerializableCookie {
            return SerializableCookie(
                name = cookie.name,
                value = cookie.value,
                expiresAt = cookie.expiresAt,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = cookie.hostOnly
            )
        }
    }

    // SerializableCookie -> OkHttp Cookie
    fun toCookie(): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)

        // 还原 domain
        // 注意：OkHttp 逻辑是设置 domain() 会导致 hostOnly=false
        // 如果原 cookie 是 hostOnly，理论上应该用 hostOnlyDomain() (但这是包级私有 API)
        // 这里使用标准 domain() 设置，满足 99% 的业务需求
        builder.domain(domain)

        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()

        return builder.build()
    }
}

object CookieSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(cookies: List<Cookie>): String {
        // 1. 将 OkHttp Cookie 映射为 代理类
        val serializableList = cookies.map { SerializableCookie.from(it) }
        // 2. 序列化代理类列表
        return json.encodeToString(serializableList)
    }

    fun decode(jsonString: String?): List<Cookie> {
        if (jsonString.isNullOrBlank()) return emptyList()

        return try {
            // 1. 反序列化为 代理类列表
            val serializableList = json.decodeFromString<List<SerializableCookie>>(jsonString)
            // 2. 映射回 OkHttp Cookie
            serializableList.map { it.toCookie() }
        } catch (e: Exception) {
            e.printStackTrace() // 解析失败（如格式错误）返回空列表
            emptyList()
        }
    }

    fun saveFromResponse(url: String, cookies: String) {
        InMemoryCookieJar.instance.saveFromResponse(
            url.toHttpUrl(),
            decode(cookies)
        )
    }
}