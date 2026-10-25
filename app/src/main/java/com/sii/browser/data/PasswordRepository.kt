package com.sii.browser.data

import com.sii.browser.core.privacy.PasswordCrypto
import com.sii.browser.data.db.PasswordDao
import com.sii.browser.data.db.PasswordItem
import kotlinx.coroutines.flow.Flow

/**
 * 密码库仓库：按域名/用户名保存账号密码（密码经 [PasswordCrypto] 加密）。
 */
class PasswordRepository(private val dao: PasswordDao) {

    fun all(): Flow<List<PasswordItem>> = dao.all()

    suspend fun byHost(host: String): List<PasswordItem> = dao.byHost(host.lowercase())

    /** 保存或更新（同域名+用户名视为同一条）。 */
    suspend fun save(host: String, username: String, password: String): Boolean {
        val normalizedHost = host.lowercase()
        val now = System.currentTimeMillis()
        val aad = "$normalizedHost|$username"
        val cipher = PasswordCrypto.encrypt(password, aad) ?: return false
        val existing = dao.find(normalizedHost, username)
        if (existing != null) {
            dao.update(existing.copy(cipher = cipher, updatedAt = now))
        } else {
            dao.insert(
                PasswordItem(
                    host = normalizedHost,
                    username = username,
                    cipher = cipher,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        return true
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.clearAll()

    /** 解密条目密码（失败返回 null，例如密钥丢失）。 */
    fun decrypt(item: PasswordItem): String? =
        PasswordCrypto.decrypt(item.cipher, "${item.host}|${item.username}")
}
