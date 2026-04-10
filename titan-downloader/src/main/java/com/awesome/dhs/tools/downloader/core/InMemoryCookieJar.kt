package com.awesome.dhs.tools.downloader.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * FileName: ICookieJar
 * Author: haosen
 * Date: 1/28/2026 8:32 AM
 * Description:
 **/
class InMemoryCookieJar private constructor(): CookieJar {

    companion object {
        val instance: InMemoryCookieJar by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            InMemoryCookieJar()
        }
    }

    // 使用 ConcurrentHashMap 保证线程安全
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // 保存 Cookie，以 Host 为 Key
        cookieStore[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // 加载 Cookie，如果为空则返回空列表
        val cookies = cookieStore[url.host]
        return cookies ?: ArrayList()
    }
}