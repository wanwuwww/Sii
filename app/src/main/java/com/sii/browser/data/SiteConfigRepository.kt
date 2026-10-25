package com.sii.browser.data

import com.sii.browser.data.db.SiteConfigDao
import com.sii.browser.data.db.SiteConfigEntity

/**
 * 站点配置仓库。
 *
 * 维护内存缓存，供主线程按 host 读取（避免主线程查库）。
 */
class SiteConfigRepository(private val dao: SiteConfigDao) {

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    /** 从库中全量刷新内存缓存（在后台线程调用）。 */
    fun refresh() {
        cache = dao.all().associate { it.host to it.config }
    }

    fun all(): List<SiteConfigEntity> = dao.all()

    /** 内存缓存视图（host → config JSON），供 UI 直接列出。 */
    fun cachedAll(): Map<String, String> = cache

    /**
     * 供主线程按 host 读配置 JSON；只精确命中。
     * m./www. 各自独立一条规则（用户用双规则接力切手机/电脑模式），不做跨前缀回退。
     */
    fun cachedConfig(host: String): String? {
        val key = host.lowercase()
        return cache[key]
    }

    fun put(host: String, config: String) {
        val key = host.lowercase()
        dao.put(SiteConfigEntity(key, config, System.currentTimeMillis()))
        cache = cache + (key to config)
    }

    fun remove(host: String) {
        val key = host.lowercase()
        dao.remove(key)
        cache = cache - key
    }

    /** 主线程先更新内存缓存，持久化由调用方在后台完成。 */
    fun cachePut(host: String, config: String) {
        cache = cache + (host.lowercase() to config)
    }

    fun cacheRemove(host: String) {
        cache = cache - host.lowercase()
    }

    suspend fun clearAll() {
        dao.clearAll()
        cache = emptyMap()
    }
}
