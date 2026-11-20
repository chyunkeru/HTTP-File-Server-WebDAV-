package com.aistudio.server

import com.aistudio.db.SharedFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object WebDavHandler {
    private val rfc1123DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
    
    private val iso8601DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    fun formatDateRfc1123(time: Long): String = rfc1123DateFormat.format(Date(time))
    fun formatDateIso8601(time: Long): String = iso8601DateFormat.format(Date(time))

    fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun buildVirtualRootPropfindXML(folders: List<SharedFolder>, depth: String = "1"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n")

        // Root entry
        sb.append("  <D:response>\n")
        sb.append("    <D:href>/</D:href>\n")
        sb.append("    <D:propstat>\n")
        sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
        sb.append("      <D:prop>\n")
        sb.append("        <D:displayname>/</D:displayname>\n")
        sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
        sb.append("        <D:getlastmodified>${formatDateRfc1123(System.currentTimeMillis())}</D:getlastmodified>\n")
        sb.append("      </D:prop>\n")
        sb.append("    </D:propstat>\n")
        sb.append("  </D:response>\n")

        // Folder entries
        if (depth != "0") {
            for (folder in folders) {
                val folderFile = File(folder.absolutePath)
                val name = folder.name
                val escapedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val lastModified = if (RootAdvancedFeatures.fileExists(folderFile)) folderFile.lastModified() else System.currentTimeMillis()

                sb.append("  <D:response>\n")
                sb.append("    <D:href>/${escapedName}/</D:href>\n")
                sb.append("    <D:propstat>\n")
                sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
                sb.append("      <D:prop>\n")
                sb.append("        <D:displayname>${escapeXml(name)}</D:displayname>\n")
                sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
                sb.append("        <D:getlastmodified>${formatDateRfc1123(lastModified)}</D:getlastmodified>\n")
                sb.append("      </D:prop>\n")
                sb.append("    </D:propstat>\n")
                sb.append("  </D:response>\n")
            }
        }

        sb.append("</D:multistatus>")
        return sb.toString()
    }

    fun buildFolderPropfindXML(folderName: String, parentDir: File, virtualPath: String, depth: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n")

        // clean and encode path segments
        fun encodePath(path: String): String {
            return path.split("/").joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
        }

        val rawVirtualPath = if (virtualPath.endsWith("/")) virtualPath else "$virtualPath/"
        val cleanVirtualPath = encodePath(rawVirtualPath)

        // Self entry
        val selfDisplayName = if (cleanVirtualPath.trim('/').equals(encodePath(folderName).trim('/'), ignoreCase = true) || parentDir.name.isEmpty()) {
            folderName
        } else {
            parentDir.name
        }
        sb.append("  <D:response>\n")
        sb.append("    <D:href>${cleanVirtualPath}</D:href>\n")
        sb.append("    <D:propstat>\n")
        sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
        sb.append("      <D:prop>\n")
        sb.append("        <D:displayname>${escapeXml(selfDisplayName)}</D:displayname>\n")
        sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
        sb.append("        <D:getlastmodified>${formatDateRfc1123(parentDir.lastModified())}</D:getlastmodified>\n")
        sb.append("      </D:prop>\n")
        sb.append("    </D:propstat>\n")
        sb.append("  </D:response>\n")

        // Include immediate children if depth is "1"
        if (depth == "1" && RootAdvancedFeatures.fileExists(parentDir) && RootAdvancedFeatures.fileIsDirectory(parentDir)) {
            val children = RootAdvancedFeatures.fileListFiles(parentDir).toTypedArray()
            for (child in children) {
                val childEscapedName = java.net.URLEncoder.encode(child.name, "UTF-8").replace("+", "%20")
                val childVirtualPath = if (RootAdvancedFeatures.fileIsDirectory(child)) {
                    "${cleanVirtualPath}${childEscapedName}/"
                } else {
                    "${cleanVirtualPath}${childEscapedName}"
                }

                sb.append("  <D:response>\n")
                sb.append("    <D:href>${childVirtualPath}</D:href>\n")
                sb.append("    <D:propstat>\n")
                sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
                sb.append("      <D:prop>\n")
                sb.append("        <D:displayname>${escapeXml(child.name)}</D:displayname>\n")
                if (RootAdvancedFeatures.fileIsDirectory(child)) {
                    sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
                } else {
                    sb.append("        <D:resourcetype/>\n")
                    sb.append("        <D:getcontentlength>${RootAdvancedFeatures.fileLength(child)}</D:getcontentlength>\n")
                }
                sb.append("        <D:getlastmodified>${formatDateRfc1123(child.lastModified())}</D:getlastmodified>\n")
                sb.append("      </D:prop>\n")
                sb.append("    </D:propstat>\n")
                sb.append("  </D:response>\n")
            }
        }

        sb.append("</D:multistatus>")
        return sb.toString()
    }

    fun buildFilePropfindXML(file: File, virtualPath: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n")

        fun encodePath(path: String): String {
            return path.split("/").joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
        }

        val cleanVirtualPath = encodePath(virtualPath)

        sb.append("  <D:response>\n")
        sb.append("    <D:href>${cleanVirtualPath}</D:href>\n")
        sb.append("    <D:propstat>\n")
        sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
        sb.append("      <D:prop>\n")
        sb.append("        <D:displayname>${escapeXml(file.name)}</D:displayname>\n")
        sb.append("        <D:resourcetype/>\n")
        sb.append("        <D:getcontentlength>${RootAdvancedFeatures.fileLength(file)}</D:getcontentlength>\n")
        sb.append("        <D:getlastmodified>${formatDateRfc1123(file.lastModified())}</D:getlastmodified>\n")
        sb.append("      </D:prop>\n")
        sb.append("    </D:propstat>\n")
        sb.append("  </D:response>\n")
        sb.append("</D:multistatus>")
        return sb.toString()
    }

    fun buildLockSuccessXML(path: String, lockToken: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:">
  <D:lockdiscovery>
    <D:activelock>
      <D:locktype><D:write/></D:locktype>
      <D:lockscope><D:exclusive/></D:lockscope>
      <D:depth>Infinity</D:depth>
      <D:owner><D:href>DavClnt</D:href></D:owner>
      <D:timeout>Second-3600</D:timeout>
      <D:locktoken><D:href>$lockToken</D:href></D:locktoken>
      <D:lockroot><D:href>${escapeXml(path)}</D:href></D:lockroot>
    </D:activelock>
  </D:lockdiscovery>
</D:prop>""".trimIndent()
    }
}
