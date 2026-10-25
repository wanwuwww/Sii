package com.sii.browser.core.privacy

import android.net.Uri

/** 去掉常见的跨站跟踪参数，保留功能性参数。 */
object PrivacyUrlCleaner {

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gclsrc", "msclkid", "yclid", "twclid",
        "igshid", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "vero_id",
        "oly_anon_id", "oly_enc_id", "ref_src", "ref_url", "spm", "scm"
    )

    fun clean(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val query = uri.query ?: return url
        val parts = query.split('&')
        val kept = parts.filter { part ->
            val key = part.substringBefore('=').lowercase()
            key !in TRACKING_PARAMS && !key.startsWith("utm_")
        }
        if (kept.size == parts.size) return url
        val builder = uri.buildUpon().encodedQuery(
            if (kept.isEmpty()) null else kept.joinToString("&")
        )
        return builder.build().toString()
    }
}
