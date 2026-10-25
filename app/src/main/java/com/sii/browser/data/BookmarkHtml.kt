package com.sii.browser.data

import com.sii.browser.data.db.BookmarkFolder
import com.sii.browser.data.db.BookmarkItem

/**
 * 书签的 Netscape Bookmark HTML 导入/导出（浏览器通用格式）。
 *
 * 导出保留文件夹层级；导入为简化解析：按文件顺序记住最近的 `<H3>` 文件夹名，
 * 同名文件夹复用，层级会被拉平到根下（普通书签文件无此问题）。
 */
object BookmarkHtml {

    data class ImportedEntry(val folder: String?, val title: String, val url: String)

    fun export(items: List<BookmarkItem>, folders: List<BookmarkFolder>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")
        renderLevel(sb, "    ", null, folders, items)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private fun renderLevel(
        sb: StringBuilder,
        indent: String,
        parentId: String?,
        folders: List<BookmarkFolder>,
        items: List<BookmarkItem>
    ) {
        items.filter { it.folderId == parentId }
            .sortedBy { it.ordering }
            .forEach { item ->
                sb.append(indent)
                    .append("<DT><A HREF=\"").append(escape(item.url)).append("\">")
                    .append(escape(item.title.ifEmpty { item.url }))
                    .append("</A>\n")
            }
        folders.filter { it.parentFolderId == parentId }
            .sortedBy { it.ordering }
            .forEach { folder ->
                sb.append(indent).append("<DT><H3>").append(escape(folder.title)).append("</H3>\n")
                sb.append(indent).append("<DL><p>\n")
                renderLevel(sb, "$indent    ", folder.id, folders, items)
                sb.append(indent).append("</DL><p>\n")
            }
    }

    fun parse(html: String): List<ImportedEntry> {
        val pattern = Regex(
            "<H3[^>]*>(.*?)</H3>|<A\\s[^>]*?HREF=\"([^\"]+)\"[^>]*>(.*?)</A>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val entries = mutableListOf<ImportedEntry>()
        var currentFolder: String? = null
        pattern.findAll(html).forEach { match ->
            val folderName = match.groupValues[1]
            val href = match.groupValues[2]
            val title = match.groupValues[3]
            when {
                folderName.isNotBlank() -> currentFolder = unescape(folderName).trim()
                href.isNotBlank() -> entries.add(
                    ImportedEntry(
                        folder = currentFolder,
                        title = unescape(title).ifBlank { href },
                        url = href.trim()
                    )
                )
            }
        }
        return entries
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
