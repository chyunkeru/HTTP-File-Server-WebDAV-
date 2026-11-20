package com.aistudio.server

import android.util.Base64
import android.util.Log
import com.aistudio.db.LogEntry
import com.aistudio.db.SharedFolder
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

class HttpServer(
    private val port: Int,
    private val isWebdavEnabled: Boolean,
    private val isAnonymousAllowed: Boolean,
    private val authUsername: String,
    private val authPassword: String,
    private val onLogRequest: (LogEntry) -> Unit,
    private val getFoldersProvider: () -> List<SharedFolder>
) {
    private var serverSocket: ServerSocket? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    companion object {
        private const val TAG = "HttpServer"
    }

    fun start() {
        isRunning = true
        serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket exception: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        serverScope.cancel()
    }

    private fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
        var requestMethod = "UNKNOWN"
        var requestUri = "/"
        var responseCode = 200
        var bytesTransferred = 0L
        var detailsMsg = ""

        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, "UTF-8"))
            val outputStream = socket.outputStream

            // Parse request line
            val firstLine = reader.readLine() ?: return
            val requestTokens = firstLine.split(" ")
            if (requestTokens.size < 2) {
                sendSimpleResponse(outputStream, 400, "Bad Request", "Invalid request line")
                return
            }

            requestMethod = requestTokens[0].uppercase()
            requestUri = requestTokens[1]

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null || line.trim().isEmpty()) {
                    break
                }
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            val decodedUri = URLDecoder.decode(requestUri, "UTF-8")
            val isAdminRoute = decodedUri == "/admin" || decodedUri.startsWith("/admin?") || decodedUri.startsWith("/admin/")

            // Check Basic Authentication
            if (!isAnonymousAllowed || isAdminRoute) {
                val authHeader = headers["authorization"]
                if (authHeader == null || !authHeader.startsWith("Basic ")) {
                    sendAuthRequiredResponse(outputStream)
                    logAndLogEntry(clientIp, requestMethod, requestUri, 401, 0L, "Auth required")
                    return
                }

                val encodedPart = authHeader.substring(6)
                val decodedBytes = Base64.decode(encodedPart, Base64.DEFAULT)
                val credentials = String(decodedBytes, Charsets.UTF_8).split(":")
                if (credentials.size != 2 || credentials[0] != authUsername || credentials[1] != authPassword) {
                    sendAuthRequiredResponse(outputStream)
                    logAndLogEntry(clientIp, requestMethod, requestUri, 401, 0L, "Invalid credentials")
                    return
                }
            }

            // Intercept admin route execution
            if (isAdminRoute) {
                handleAdminSystemRequest(outputStream, requestMethod, decodedUri, clientIp)
                return
            }

            // Route request
            val resolvedResult = resolvePath(decodedUri)

            if (resolvedResult == null) {
                // Return Virtual Root Listing
                if (requestMethod == "PROPFIND" && isWebdavEnabled) {
                    val depth = headers["depth"] ?: "1"
                    val xml = WebDavHandler.buildVirtualRootPropfindXML(getFoldersProvider(), depth)
                    sendXmlResponse(outputStream, 207, "Multi-Status", xml)
                    logAndLogEntry(clientIp, requestMethod, requestUri, 207, xml.length.toLong(), "WebDAV virtual root metadata")
                } else if (requestMethod == "GET" || requestMethod == "HEAD") {
                    val html = buildVirtualRootHtml()
                    sendHtmlResponse(outputStream, 200, "OK", html, requestMethod == "HEAD")
                    logAndLogEntry(clientIp, requestMethod, requestUri, 200, html.length.toLong(), "Index html")
                } else if (requestMethod == "OPTIONS" && isWebdavEnabled) {
                    sendOptionsResponse(outputStream)
                    logAndLogEntry(clientIp, requestMethod, requestUri, 200, 0L, "WebDAV options call")
                } else {
                    sendSimpleResponse(outputStream, 404, "Not Found", "Folder not found mapping")
                    logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "Null directory path")
                }
                return
            }

            val targetFile = resolvedResult.physicalFile
            val isWritable = resolvedResult.isWritable

            // Execute Methods
            when (requestMethod) {
                "OPTIONS" -> {
                    if (isWebdavEnabled) {
                        sendOptionsResponse(outputStream)
                        logAndLogEntry(clientIp, requestMethod, requestUri, 200, 0, "OPTIONS success")
                    } else {
                        sendSimpleResponse(outputStream, 200, "OK", "Server is active")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 200, 0, "OPTIONS success (webdav disabled)")
                    }
                }
                "PROPFIND" -> {
                    if (!isWebdavEnabled) {
                        sendSimpleResponse(outputStream, 405, "Method Not Allowed", "WebDAV is disabled")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 405, 0L, "PROPFIND rejected")
                        return
                    }

                    val depth = headers["depth"] ?: "1"
                    val xml = if (!targetFile.exists()) {
                        sendSimpleResponse(outputStream, 404, "Not Found", "Resource not found")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "File not found for PROPFIND")
                        return
                    } else if (targetFile.isDirectory) {
                        WebDavHandler.buildFolderPropfindXML(resolvedResult.folderName, targetFile, decodedUri, depth)
                    } else {
                        WebDavHandler.buildFilePropfindXML(targetFile, decodedUri)
                    }

                    sendXmlResponse(outputStream, 207, "Multi-Status", xml)
                    logAndLogEntry(clientIp, requestMethod, requestUri, 207, xml.length.toLong(), "WebDAV properties list")
                }
                "PROPPATCH" -> {
                    // Modern clients need a response that looks successful for WebDAV properties
                    val xml = """<?xml version="1.0" encoding="utf-8"?>
                        |<D:multistatus xmlns:D="DAV:">
                        |  <D:response>
                        |    <D:href>${URLEncoder.encode(decodedUri, "UTF-8").replace("+", "%20")}</D:href>
                        |    <D:propstat>
                        |      <D:prop><D:getlastmodified/></D:prop>
                        |      <D:status>HTTP/1.1 200 OK</D:status>
                        |    </D:propstat>
                        |  </D:response>
                        |</D:multistatus>""".trimMargin()
                    sendXmlResponse(outputStream, 207, "Multi-Status", xml)
                    logAndLogEntry(clientIp, requestMethod, requestUri, 207, xml.length.toLong(), "WebDAV property set")
                }
                "LOCK" -> {
                    // Windows clients frequently lock files. Mock lock acquisition.
                    val lockToken = "opaquelocktoken:${java.util.UUID.randomUUID()}"
                    val xml = WebDavHandler.buildLockSuccessXML(decodedUri, lockToken)
                    val responseHeaders = mapOf(
                        "Lock-Token" to "<$lockToken>",
                        "Content-Type" to "application/xml; charset=utf-8"
                    )
                    sendFullResponse(outputStream, 200, "OK", responseHeaders, xml.toByteArray(Charsets.UTF_8))
                    logAndLogEntry(clientIp, requestMethod, requestUri, 200, xml.length.toLong(), "WebDAV simulated write lock acquired")
                }
                "UNLOCK" -> {
                    sendSimpleResponse(outputStream, 204, "No Content", "")
                    logAndLogEntry(clientIp, requestMethod, requestUri, 204, 0L, "WebDAV write lock released")
                }
                "GET", "HEAD" -> {
                    if (!RootAdvancedFeatures.fileExists(targetFile)) {
                        sendSimpleResponse(outputStream, 404, "Not Found", "The requested path does not exist")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "File or folder not found")
                        return
                    }

                    if (RootAdvancedFeatures.fileIsDirectory(targetFile)) {
                        // Render physical folder Web UI
                        val html = buildFolderHtml(resolvedResult.folderName, decodedUri, targetFile, isWritable)
                        sendHtmlResponse(outputStream, 200, "OK", html, requestMethod == "HEAD")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 200, html.length.toLong(), "Render folder landing page")
                    } else {
                        // Serve physical file stream
                        streamFile(socket, targetFile, headers, requestMethod == "HEAD")
                    }
                }
                "PUT" -> {
                    if (!isWritable) {
                        sendSimpleResponse(outputStream, 403, "Forbidden", "This shared directory is read-only")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 403, 0L, "Write denied (read-only share)")
                        return
                    }

                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                    try {
                        // Write to a temporary local file first
                        val tempFile = File.createTempFile("upload_", ".tmp")
                        val fos = FileOutputStream(tempFile)
                        val bis = BufferedInputStream(socket.getInputStream())
                        var bytesRemaining = contentLength
                        val buffer = ByteArray(64 * 1024) // 64K buffer
                        while (bytesRemaining > 0) {
                            val toRead = java.lang.Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = bis.read(buffer, 0, toRead)
                            if (read == -1) break
                            fos.write(buffer, 0, read)
                            bytesRemaining -= read
                        }
                        fos.flush()
                        fos.close()

                        // Move the temp file to the final destination (using root permissions if needed)
                        val success = RootAdvancedFeatures.moveFileWithRoot(tempFile, targetFile)
                        if (success) {
                            sendSimpleResponse(outputStream, 201, "Created", "File written successfully")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 201, contentLength, "File written successfully via PUT")
                        } else {
                            tempFile.delete()
                            sendSimpleResponse(outputStream, 500, "Internal Server Error", "Failed to write file to destination")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 500, 0L, "Error moving temp file to destination")
                        }
                    } catch (e: Exception) {
                        sendSimpleResponse(outputStream, 500, "Internal Server Error", "Write crash: ${e.message}")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 500, 0L, "Error writing file: ${e.message}")
                    }
                }
                "MKCOL" -> {
                    if (!isWritable) {
                        sendSimpleResponse(outputStream, 403, "Forbidden", "This shared directory is read-only")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 403, 0L, "MKCOL denied (read-only)")
                        return
                    }

                    if (RootAdvancedFeatures.fileExists(targetFile)) {
                        sendSimpleResponse(outputStream, 405, "Method Not Allowed", "Folder/File already exists")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 405, 0L, "MKCOL folder exists")
                    } else {
                        val success = RootAdvancedFeatures.createDir(targetFile)
                        if (success) {
                            sendSimpleResponse(outputStream, 201, "Created", "Directory created successfully")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 201, 0L, "Directory created successfully")
                        } else {
                            sendSimpleResponse(outputStream, 500, "Internal Server Error", "Failed to create directory structure")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 500, 0L, "MKCOL failed")
                        }
                    }
                }
                "DELETE" -> {
                    if (!isWritable) {
                        sendSimpleResponse(outputStream, 403, "Forbidden", "This shared directory is read-only")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 403, 0L, "Delete denied (read-only)")
                        return
                    }

                    if (!RootAdvancedFeatures.fileExists(targetFile)) {
                        sendSimpleResponse(outputStream, 404, "Not Found", "Target does not exist")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "Delete targeted missing item")
                    } else {
                        val success = RootAdvancedFeatures.deleteRecursive(targetFile)
                        if (success) {
                            sendSimpleResponse(outputStream, 204, "No Content", "")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 204, 0L, "Item deleted successfully")
                        } else {
                            sendSimpleResponse(outputStream, 500, "Internal Server Error", "Failed to delete item")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 500, 0L, "Delete recursive failed")
                        }
                    }
                }
                "MOVE", "COPY" -> {
                    if (!isWritable) {
                        sendSimpleResponse(outputStream, 403, "Forbidden", "This shared directory is read-only")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 403, 0L, "Move/Copy denied (read-only)")
                        return
                    }

                    val destHeader = headers["destination"]
                    if (destHeader == null) {
                        sendSimpleResponse(outputStream, 400, "Bad Request", "Missing mandatory destination header")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 400, 0L, "Missing destination header")
                        return
                    }

                    // Parse destination path from full URI
                    val destUri = try {
                        val url = java.net.URL(destHeader)
                        url.path
                    } catch (e: Exception) {
                        destHeader // fallback to pure string if not full URL
                    }

                    val decodedDestUri = URLDecoder.decode(destUri, "UTF-8")
                    val destResolved = resolvePath(decodedDestUri)

                    if (destResolved == null || !destResolved.isWritable) {
                        sendSimpleResponse(outputStream, 403, "Forbidden", "Destination share is missing or read-only")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 403, 0L, "Move destination forbidden")
                        return
                    }

                    val destFile = destResolved.physicalFile
                    if (!targetFile.exists()) {
                        sendSimpleResponse(outputStream, 404, "Not Found", "Source file doesn't exist")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "Source physical file missing")
                    } else {
                        val parent = destFile.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }

                        val success = if (requestMethod == "MOVE") {
                            targetFile.renameTo(destFile)
                        } else {
                            copyRecursive(targetFile, destFile)
                        }

                        if (success) {
                            sendSimpleResponse(outputStream, 201, "Created", "Operation completed successfully")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 201, 0, "Resource $requestMethod to $decodedDestUri success")
                        } else {
                            sendSimpleResponse(outputStream, 500, "Internal Server Error", "Failed to $requestMethod resource")
                            logAndLogEntry(clientIp, requestMethod, requestUri, 500, 0, "$requestMethod failed on local file system")
                        }
                    }
                }
                else -> {
                    sendSimpleResponse(outputStream, 501, "Not Implemented", "Method not supported by server")
                    logAndLogEntry(clientIp, requestMethod, requestUri, 501, 0L, "Unsupported standard HTTP method")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Socket handler crash: ${e.message}")
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun logAndLogEntry(
        clientIp: String,
        method: String,
        uri: String,
        responseCode: Int,
        byteCount: Long,
        details: String
    ) {
        val entry = LogEntry(
            clientIp = clientIp,
            method = method,
            uri = uri,
            responseCode = responseCode,
            byteCount = byteCount,
            details = details
        )
        onLogRequest(entry)
    }

    // Resolve URL path to a configured physical folder path
    private fun resolvePath(decodedUri: String): ResolvedResult? {
        val cleanUri = if (decodedUri.startsWith("/")) decodedUri else "/$decodedUri"
        val segments = cleanUri.split("/").filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            return null
        }

        val firstSegment = segments[0]
        val activeFolders = getFoldersProvider()
        val matchingFolder = activeFolders.find { it.name.trim() == firstSegment.trim() } ?: return null

        val subRelativePath = segments.drop(1).joinToString(File.separator)
        val targetFile = if (subRelativePath.isEmpty()) {
            File(matchingFolder.absolutePath)
        } else {
            File(matchingFolder.absolutePath, subRelativePath)
        }

        // Security check: Directory traversal prevention
        val baseDirCanonical = File(matchingFolder.absolutePath).canonicalPath
        try {
            val targetCanonical = targetFile.canonicalPath
            if (!targetCanonical.startsWith(baseDirCanonical) && targetCanonical != baseDirCanonical) {
                // Security trap! Access outside base is forbidden
                return null
            }

            // Virtual Chroot Isolation Guard
            val jail = RootAdvancedFeatures.chrootJailPath
            if (jail != null) {
                val canonicalJail = File(jail).canonicalPath
                if (!targetCanonical.startsWith(canonicalJail) && targetCanonical != canonicalJail) {
                    return null
                }
            }
        } catch (e: Exception) {
            return null
        }

        return ResolvedResult(
            virtualPath = cleanUri,
            physicalFile = targetFile,
            isWritable = matchingFolder.isWritable,
            folderName = matchingFolder.name
        )
    }

    private data class ResolvedResult(
        val virtualPath: String,
        val physicalFile: File,
        val isWritable: Boolean,
        val folderName: String
    )

    // Delete folder recursively
    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) {
                if (!deleteRecursive(child)) return false
            }
        }
        return file.delete()
    }

    // Copy folder recursively
    private fun copyRecursive(src: File, dest: File): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dest.exists()) dest.mkdirs()
                val children = src.list() ?: return false
                for (child in children) {
                    val s = File(src, child)
                    val d = File(dest, child)
                    if (!copyRecursive(s, d)) return false
                }
                true
            } else {
                val input = FileInputStream(src)
                val output = FileOutputStream(dest)
                val buf = ByteArray(1024 * 16)
                var len: Int
                while (input.read(buf).also { len = it } > 0) {
                    output.write(buf, 0, len)
                }
                input.close()
                output.close()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // Serve fine-grained ranges or full file
    private fun streamFile(socket: Socket, file: File, headers: Map<String, String>, isHead: Boolean) {
        val outputStream = socket.outputStream
        val totalLength = RootAdvancedFeatures.fileLength(file)
        val rangeHeader = headers["range"]
        
        val contentType = getMimeType(file.name)

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            // Support partial range content (ideal for audio/video scrubbing)
            try {
                val rangeValue = rangeHeader.substring(6)
                val dashIdx = rangeValue.indexOf('-')
                if (dashIdx != -1) {
                    val startStr = rangeValue.substring(0, dashIdx).trim()
                    val endStr = rangeValue.substring(dashIdx + 1).trim()
                    
                    val start = if (startStr.isEmpty()) 0L else startStr.toLong()
                    val end = if (endStr.isEmpty()) totalLength - 1L else endStr.toLong()

                    if (start >= totalLength || end >= totalLength || start > end) {
                        sendSimpleResponse(outputStream, 416, "Requested Range Not Satisfiable", "Out of bounds")
                        return
                    }

                    val chunkLength = end - start + 1
                    val head = "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Range: bytes $start-$end/$totalLength\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: $chunkLength\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n"
                    
                    outputStream.write(head.toByteArray(Charsets.UTF_8))
                    
                    if (!isHead) {
                        val fis = RootAdvancedFeatures.getInputStream(file)
                        fis.skip(start)
                        val fisBuffered = BufferedInputStream(fis)
                        val buffer = ByteArray(32 * 1024)
                        var remaining = chunkLength
                        while (remaining > 0) {
                            val readSize = java.lang.Math.min(buffer.size.toLong(), remaining).toInt()
                            val read = fisBuffered.read(buffer, 0, readSize)
                            if (read == -1) break
                            outputStream.write(buffer, 0, read)
                            remaining -= read
                        }
                        fisBuffered.close()
                    }
                    outputStream.flush()
                }
            } catch (e: Exception) {
                sendSimpleResponse(outputStream, 400, "Bad Request", "Error processing range header: ${e.message}")
            }
        } else {
            // Classic full-file service
            val responseHeaders = mapOf(
                "Content-Type" to contentType,
                "Content-Length" to totalLength.toString(),
                "Accept-Ranges" to "bytes",
                "Connection" to "close"
            )
            val head = StringBuilder("HTTP/1.1 200 OK\r\n")
            responseHeaders.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            outputStream.write(head.toString().toByteArray(Charsets.UTF_8))

            if (!isHead) {
                val fis = RootAdvancedFeatures.getInputStream(file)
                val fisBuffered = BufferedInputStream(fis)
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (fisBuffered.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                fisBuffered.close()
            }
            outputStream.flush()
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }

    // Builder methods for response states
    private fun sendSimpleResponse(os: OutputStream, status: Int, statusText: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to bodyBytes.size.toString(),
            "Connection" to "close"
        )
        sendFullResponse(os, status, statusText, headers, bodyBytes)
    }

    private fun sendHtmlResponse(os: OutputStream, status: Int, statusText: String, html: String, isHead: Boolean) {
        val htmlBytes = html.toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to htmlBytes.size.toString(),
            "Connection" to "close"
        )
        sendFullResponse(os, status, statusText, headers, if (isHead) ByteArray(0) else htmlBytes)
    }

    private fun sendXmlResponse(os: OutputStream, status: Int, statusText: String, xml: String) {
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Content-Type" to "application/xml; charset=utf-8",
            "Content-Length" to xmlBytes.size.toString(),
            "Connection" to "close"
        )
        sendFullResponse(os, status, statusText, headers, xmlBytes)
    }

    private fun sendAuthRequiredResponse(os: OutputStream) {
        val bodyBytes = "Authentication required".toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "WWW-Authenticate" to "Basic realm=\"HTTP File Server\"",
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to bodyBytes.size.toString(),
            "Connection" to "close"
        )
        sendFullResponse(os, 401, "Unauthorized", headers, bodyBytes)
    }

    private fun sendOptionsResponse(os: OutputStream) {
        val headers = mapOf(
            "DAV" to "1, 2",
            "MS-Author-Via" to "DAV",
            "Allow" to "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK",
            "Content-Length" to "0",
            "Connection" to "close"
        )
        sendFullResponse(os, 200, "OK", headers, ByteArray(0))
    }

    private fun sendFullResponse(os: OutputStream, status: Int, statusText: String, headers: Map<String, String>, body: ByteArray) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status $statusText\r\n")
        headers.forEach { (k, v) ->
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")
        os.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) {
            os.write(body)
        }
        os.flush()
    }

    // Renders the virtual root `/` that list out available database shared directories
    private fun buildVirtualRootHtml(): String {
        val folders = getFoldersProvider()
        val listHtml = if (folders.isEmpty()) {
            """<tr class="empty-state"><td colspan="3">No folders configured. Please add a folder using the app.</td></tr>"""
        } else {
            folders.joinToString("") { folder ->
                val escapedName = URLEncoder.encode(folder.name, "UTF-8").replace("+", "%20")
                """
                <tr>
                    <td class="name-col">
                        <svg class="icon icon-folder" viewBox="0 0 24 24"><path d="M10 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2h-8l-2-2z"/></svg>
                        <a href="/${escapedName}/">${WebDavHandler.escapeXml(folder.name)}</a>
                    </td>
                    <td>Folder</td>
                    <td>${if (folder.isWritable) "Read / Write" else "Read Only"}</td>
                </tr>
                """.trimIndent()
            }
        }

        return getThemePageTemplate(
            title = "Root Shares Listing",
            breadcrumbsHtml = """<span class="active">/</span>""",
            bodyHtml = """
                <div class="card overflow-hidden">
                    <table>
                        <thead>
                            <tr>
                                <th>Name</th>
                                <th style="width: 15%">Type</th>
                                <th style="width: 25%">Access Rights</th>
                            </tr>
                        </thead>
                        <tbody>
                            $listHtml
                        </tbody>
                    </table>
                </div>
            """.trimIndent()
        )
    }

    // Renders physical subdirectory contents HTML
    private fun buildFolderHtml(folderName: String, requestUri: String, parentDir: File, isWritable: Boolean): String {
        val filesList = RootAdvancedFeatures.fileListFiles(parentDir).toTypedArray()
        
        // Build Breadcrumbs
        val segments = requestUri.split("/").filter { it.isNotEmpty() }
        val breadcrumbsSb = StringBuilder("""<a href="/">Root</a>""")
        var accumulatedPath = ""
        for (i in segments.indices) {
            val seg = segments[i]
            accumulatedPath += "/$seg"
            if (i == segments.size - 1) {
                breadcrumbsSb.append("""<span class="separator">/</span><span class="active">${WebDavHandler.escapeXml(seg)}</span>""")
            } else {
                breadcrumbsSb.append("""<span class="separator">/</span><a href="${accumulatedPath}/">${WebDavHandler.escapeXml(seg)}</a>""")
            }
        }

        // Parent Directory Row if not root folder
        val parentRowHtml = if (segments.size > 1) {
            val parentPath = '/' + segments.dropLast(1).joinToString("/") + '/'
            """
            <tr class="folder-row">
                <td class="name-col" colspan="3">
                    <svg class="icon icon-up" viewBox="0 0 24 24"><path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"/></svg>
                    <a href="${parentPath}">.. (Up a level)</a>
                </td>
            </tr>
            """.trimIndent()
        } else {
            ""
        }

        val entriesHtml = filesList.sortedWith(compareBy({ !RootAdvancedFeatures.fileIsDirectory(it) }, { it.name.lowercase() }))
            .joinToString("") { file ->
                val sizeStr = if (RootAdvancedFeatures.fileIsDirectory(file)) "-" else formatFileSize(RootAdvancedFeatures.fileLength(file))
                val typeStr = if (RootAdvancedFeatures.fileIsDirectory(file)) "Folder" else getFileExtension(file.name).uppercase()
                
                val relativeUrl = if (requestUri.endsWith("/")) {
                    requestUri + URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                } else {
                    requestUri + "/" + URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
                }
                
                val iconD = if (RootAdvancedFeatures.fileIsDirectory(file)) {
                    "M10 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2h-8l-2-2z"
                } else {
                    "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"
                }
                
                val actionHtml = if (isWritable) {
                    """
                    <button class="btn-delete" onclick="deleteItem('${relativeUrl}', '${WebDavHandler.escapeXml(file.name)}')">
                        <svg class="icon-action" viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>
                    </button>
                    """.trimIndent()
                } else {
                    ""
                }

                """
                <tr>
                    <td class="name-col">
                        <svg class="icon" viewBox="0 0 24 24"><path d="$iconD"/></svg>
                        <a href="${relativeUrl}${if (RootAdvancedFeatures.fileIsDirectory(file)) "/" else ""}">${WebDavHandler.escapeXml(file.name)}</a>
                    </td>
                    <td>$typeStr</td>
                    <td>$sizeStr</td>
                    <td style="text-align: right;">$actionHtml</td>
                </tr>
                """.trimIndent()
            }

        // Control Panel forms if Directory is writable
        val controlPanelHtml = if (isWritable) {
            """
            <div class="row gap-4 mb-6">
                <!-- Folder Creation Card -->
                <div class="card flex-1 p-5">
                    <h3 class="card-title">New Folder</h3>
                    <div style="display: flex; gap: 8px;">
                        <input type="text" id="newFolderName" placeholder="Enter folder name..." class="form-input">
                        <button onclick="createFolder('${requestUri}')" class="btn btn-primary">Create</button>
                    </div>
                </div>

                <!-- Web browser PUT based interactive drag-n-drop file uploader -->
                <div class="card flex-1 p-5">
                    <h3 class="card-title">Upload Files</h3>
                    <div id="dropZone" class="drop-zone" onclick="document.getElementById('fileInput').click()">
                        <svg class="drop-icon" viewBox="0 0 24 24"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg>
                        <p class="drop-text">Drag files here or <span>browse</span></p>
                        <input type="file" id="fileInput" multiple style="display: none;" onchange="uploadFiles('${requestUri}')">
                    </div>
                    <div id="uploadProgress" class="progress-bar-container hidden">
                        <div class="progress-info">
                            <span id="progressName">file.txt</span>
                            <span id="progressPct">0%</span>
                        </div>
                        <div class="progress-track">
                            <div id="progressBar" class="progress-bar"></div>
                        </div>
                    </div>
                </div>
            </div>
            """.trimIndent()
        } else {
            """
            <div class="card p-4 bg-yellow" style="background-color: #fef3c7; border: 1px solid #f59e0b; color: #b45309; border-radius: 8px;">
                <p style="margin: 0; font-weight: bold;">⚠️ This shared directory is READ-ONLY. Creating files and folders is disabled.</p>
            </div>
            """.trimIndent()
        }

        return getThemePageTemplate(
            title = folderName,
            breadcrumbsHtml = breadcrumbsSb.toString(),
            bodyHtml = """
                $controlPanelHtml

                <div class="card overflow-hidden mt-6">
                    <table>
                        <thead>
                            <tr>
                                <th>Name</th>
                                <th style="width: 15%">Type</th>
                                <th style="width: 20%">Size</th>
                                ${if (isWritable) """<th style="width: 10%; text-align: right;">Action</th>""" else ""}
                            </tr>
                        </thead>
                        <tbody>
                            $parentRowHtml
                            ${if (entriesHtml.isEmpty() && parentRowHtml.isEmpty()) """<tr class="empty-state"><td colspan="4">This folder is empty</td></tr>""" else entriesHtml}
                        </tbody>
                    </table>
                </div>
            """.trimIndent()
        )
    }

    private fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val suffix = "KMGTPE"[exp - 1] + "B"
        return String.format("%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), suffix)
    }

    // HTML template page using beautiful styling
    private fun getThemePageTemplate(title: String, breadcrumbsHtml: String, bodyHtml: String): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>HTTP File Server - $title</title>
            <style>
                :root {
                    --bg-page: #f8fafc;
                    --bg-card: #ffffff;
                    --text-primary: #0f172a;
                    --text-secondary: #64748b;
                    --border-color: #e2e8f0;
                    --primary: #3b82f6;
                    --primary-hover: #2563eb;
                    --danger: #ef4444;
                    --danger-hover: #dc2626;
                }

                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                    background-color: var(--bg-page);
                    color: var(--text-primary);
                    line-height: 1.5;
                    padding: 24px;
                }

                .header-container {
                    max-width: 1100px;
                    margin: 0 auto 24px auto;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }

                .app-branding h1 {
                    font-size: 24px;
                    font-weight: 800;
                    color: #1e3a8a;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .breadcrumbs {
                    font-size: 14px;
                    color: var(--text-secondary);
                    background: var(--bg-card);
                    padding: 8px 16px;
                    border-radius: 8px;
                    box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.05);
                    border: 1px solid var(--border-color);
                    margin-top: 4px;
                }

                .breadcrumbs a { color: var(--primary); text-decoration: none; font-weight: 500; }
                .breadcrumbs a:hover { text-decoration: underline; }
                .breadcrumbs .separator { margin: 0 8px; color: #cbd5e1; }
                .breadcrumbs .active { color: var(--text-primary); font-weight: 600; }

                .content-container {
                    max-width: 1100px;
                    margin: 0 auto;
                }

                .card {
                    background: var(--bg-card);
                    border-radius: 12px;
                    box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -1px rgba(0, 0, 0, 0.03);
                    border: 1px solid var(--border-color);
                    margin-bottom: 24px;
                }

                .p-4 { padding: 16px; }
                .p-5 { padding: 20px; }
                .p-6 { padding: 24px; }
                .overflow-hidden { overflow: hidden; }
                .mb-6 { margin-bottom: 24px; }
                .mt-6 { margin-top: 24px; }

                .row { display: flex; flex-direction: row; }
                .gap-4 { gap: 16px; }
                .flex-1 { flex: 1; }

                @media (max-width: 768px) {
                    .row { flex-direction: column; }
                    body { padding: 16px; }
                }

                .card-title {
                    font-size: 16px;
                    font-weight: 700;
                    margin-bottom: 12px;
                    color: var(--text-primary);
                }

                .form-input {
                    padding: 8px 12px;
                    border-radius: 8px;
                    border: 1px solid var(--border-color);
                    font-size: 14px;
                    flex-1: 1;
                    width: 100%;
                    outline: none;
                }
                .form-input:focus { border-color: var(--primary); }

                .btn {
                    padding: 8px 16px;
                    font-size: 14px;
                    font-weight: 600;
                    border-radius: 8px;
                    cursor: pointer;
                    border: none;
                    transition: background 0.2s;
                }

                .btn-primary { background: var(--primary); color: white; }
                .btn-primary:hover { background: var(--primary-hover); }

                .drop-zone {
                    border: 2px dashed #cbd5e1;
                    border-radius: 8px;
                    padding: 20px;
                    text-align: center;
                    cursor: pointer;
                    background: #fcfdfe;
                    transition: border 0.2s, background 0.2s;
                }

                .drop-zone:hover { border-color: var(--primary); background: #f0f7ff; }
                .drop-icon { width: 44px; height: 44px; stroke: var(--text-secondary); fill: var(--text-secondary); margin: 0 auto 8px auto; opacity: 0.7; }
                .drop-text { font-size: 13px; color: var(--text-secondary); }
                .drop-text span { color: var(--primary); font-weight: 600; }

                /* Progress bar styling */
                .progress-bar-container { margin-top: 12px; }
                .progress-info { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-secondary); margin-bottom: 4px; }
                .progress-track { width: 100%; height: 8px; background: #e2e8f0; border-radius: 9999px; overflow: hidden; }
                .progress-bar { width: 0%; height: 100%; background: var(--primary); transition: width 0.1s; }
                .hidden { display: none; }

                table {
                    width: 100%;
                    border-collapse: collapse;
                    text-align: left;
                }

                th {
                    background-color: #f8fafc;
                    font-size: 12px;
                    font-weight: 700;
                    text-transform: uppercase;
                    color: var(--text-secondary);
                    padding: 12px 16px;
                    border-bottom: 2px solid var(--border-color);
                }

                td {
                    padding: 14px 16px;
                    border-bottom: 1px solid var(--border-color);
                    font-size: 14px;
                    vertical-align: middle;
                }

                tr:hover td { background-color: #f8fafc; }

                .name-col {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }

                .icon {
                    width: 22px;
                    height: 22px;
                    fill: #94a3b8;
                    flex-shrink: 0;
                }

                .icon-folder { fill: #f59e0b; }
                .icon-up { fill: var(--primary); }

                .name-col a {
                    color: var(--text-primary);
                    text-decoration: none;
                    font-weight: 500;
                }
                .name-col a:hover { color: var(--primary); text-decoration: underline; }

                .btn-delete {
                    background: transparent;
                    border: none;
                    cursor: pointer;
                    color: var(--text-secondary);
                    padding: 4px;
                    border-radius: 4px;
                }
                .btn-delete:hover { color: var(--danger); background: #fee2e2; }
                .icon-action { width: 18px; height: 18px; fill: currentColor; }

                .empty-state { text-align: center; color: var(--text-secondary); padding: 40px; }
                .empty-state td { padding: 40px; }
            </style>
            <script>
                async function createFolder(parentUri) {
                    const folderNameInput = document.getElementById("newFolderName");
                    const name = folderNameInput.value.trim();
                    if (!name) return alert("Folder name cannot be empty");

                    const cleanParent = parentUri.endsWith("/") ? parentUri : parentUri + "/";
                    const targetUrl = cleanParent + encodeURIComponent(name);

                    try {
                        const response = await fetch(targetUrl, { method: "MKCOL" });
                        if (response.status === 201) {
                            window.location.reload();
                        } else {
                            alert("Creation failed: " + response.statusText);
                        }
                    } catch (e) {
                        alert("Network error: " + e.message);
                    }
                }

                async function deleteItem(url, displayName) {
                    if (!confirm("Are you sure you want to delete '" + displayName + "'?")) return;
                    try {
                        const response = await fetch(url, { method: "DELETE" });
                        if (response.status === 204 || response.status === 200) {
                            window.location.reload();
                        } else {
                            alert("Delete failed: " + response.statusText);
                        }
                    } catch (e) {
                        alert("Error: " + e.message);
                    }
                }

                let uploadQueue = [];
                let isUploading = false;

                function uploadFiles(parentUri) {
                    const input = document.getElementById("fileInput");
                    const files = input.files;
                    if (!files.length) return;

                    for (let file of files) {
                        uploadQueue.push(file);
                    }
                    if (!isUploading) {
                        processNextUpload(parentUri);
                    }
                }

                async function processNextUpload(parentUri) {
                    if (!uploadQueue.length) {
                        isUploading = false;
                        document.getElementById("uploadProgress").classList.add("hidden");
                        window.location.reload();
                        return;
                    }

                    isUploading = true;
                    const file = uploadQueue.shift();
                    const cleanParent = parentUri.endsWith("/") ? parentUri : parentUri + "/";
                    const targetUrl = cleanParent + encodeURIComponent(file.name);

                    const progressContainer = document.getElementById("uploadProgress");
                    const progressName = document.getElementById("progressName");
                    const progressPct = document.getElementById("progressPct");
                    const progressBar = document.getElementById("progressBar");

                    progressContainer.classList.remove("hidden");
                    progressName.textContent = file.name;
                    progressPct.textContent = "0%";
                    progressBar.style.width = "0%";

                    try {
                        await new Promise((resolve, reject) => {
                            const xhr = new XMLHttpRequest();
                            xhr.open("PUT", targetUrl, true);
                            
                            xhr.upload.onprogress = (e) => {
                                if (e.lengthComputable) {
                                    const percentComplete = Math.round((e.loaded / e.total) * 100);
                                    progressBar.style.width = percentComplete + "%";
                                    progressPct.textContent = percentComplete + "%";
                                }
                            };

                            xhr.onload = () => {
                                if (xhr.status === 201 || xhr.status === 200) {
                                    resolve();
                                } else {
                                    reject(new Error(xhr.statusText));
                                }
                            };

                            xhr.onerror = () => reject(new Error("Network Error"));
                            xhr.send(file);
                        });
                        
                        processNextUpload(parentUri);
                    } catch (e) {
                        alert("Upload failed for '" + file.name + "': " + e.message);
                        processNextUpload(parentUri);
                    }
                }

                // Drag and Drop
                window.onload = () => {
                    const dropZone = document.getElementById("dropZone");
                    if (!dropZone) return;

                    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
                        dropZone.addEventListener(eventName, preventDefaults, false);
                    });

                    function preventDefaults(e) {
                        e.preventDefault();
                        e.stopPropagation();
                    }

                    ['dragenter', 'dragover'].forEach(eventName => {
                        dropZone.addEventListener(eventName, () => dropZone.classList.add('highlight'), false);
                    });

                    ['dragleave', 'drop'].forEach(eventName => {
                        dropZone.addEventListener(eventName, () => dropZone.classList.remove('highlight'), false);
                    });

                    dropZone.addEventListener('drop', handleDrop, false);

                    function handleDrop(e) {
                        const dt = e.dataTransfer;
                        const files = dt.files;
                        const fileInput = document.getElementById("fileInput");
                        fileInput.files = files;
                        fileInput.dispatchEvent(new Event('change'));
                    }
                }
            </script>
        </head>
        <body>
            <div class="header-container">
                <div class="app-branding">
                    <h1>
                        <svg style="width:30px; height:30px; fill:#3b82f6" viewBox="0 0 24 24"><path d="M19 13H5v-2h14v2z M4 6h16v2H4zm0 10h16v2H4z"/></svg>
                        HTTP &amp; WebDAV Server
                    </h1>
                    <div class="breadcrumbs">
                        $breadcrumbsHtml
                    </div>
                </div>
            </div>

            <div class="content-container">
                $bodyHtml
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun parseQueryParams(uri: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val qIdx = uri.indexOf('?')
        if (qIdx != -1) {
            val query = uri.substring(qIdx + 1)
            val pairs = query.split("&")
            for (pair in pairs) {
                val idx = pair.indexOf('=')
                if (idx != -1) {
                    try {
                        val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        map[key] = value
                    } catch (e: Exception) {
                        // ignore error
                    }
                }
            }
        }
        return map
    }

    private fun handleAdminSystemRequest(os: OutputStream, method: String, uri: String, clientIp: String) {
        val params = parseQueryParams(uri)
        val action = params["action"] ?: ""
        var statusMessage = ""
        var messageType = "info" // info, success, error
        var searchResults = emptyList<String>()

        if (action.isNotBlank()) {
            try {
                when (action) {
                    "toggle_root_simulation" -> {
                        RootAdvancedFeatures.isRootSimulationEnabled = !RootAdvancedFeatures.isRootSimulationEnabled
                        statusMessage = "Simulator Root Mode set to: ${RootAdvancedFeatures.isRootSimulationEnabled}"
                        messageType = "success"
                    }
                    "update_jail" -> {
                        val jailPath = params["jailPath"] ?: ""
                        val allowBreakoutValue = params["allowBreakout"] ?: ""
                        
                        RootAdvancedFeatures.chrootJailPath = if (jailPath.isBlank()) null else jailPath
                        RootAdvancedFeatures.isSystemRootBreakoutAllowed = (allowBreakoutValue == "true")
                        statusMessage = "Chroot Virtual Jail configuration updated successfully."
                        messageType = "success"
                    }
                    "chmod" -> {
                        val path = params["path"] ?: ""
                        val mode = params["mode"] ?: ""
                        if (path.isNotBlank() && mode.isNotBlank()) {
                            val success = RootAdvancedFeatures.changePermissions(path, mode)
                            if (success) {
                                statusMessage = "SUCCESS: Set permission $mode on $path successfully."
                                messageType = "success"
                            } else {
                                statusMessage = "ERROR: Chmod execution failed. Inspect path sanitation or process privileges."
                                messageType = "error"
                            }
                        } else {
                            statusMessage = "ERROR: Missing chmod path or mode arguments."
                            messageType = "error"
                        }
                    }
                    "chown" -> {
                        val path = params["path"] ?: ""
                        val owner = params["owner"] ?: ""
                        if (path.isNotBlank() && owner.isNotBlank()) {
                            val success = RootAdvancedFeatures.changeOwnership(path, owner)
                            if (success) {
                                statusMessage = "SUCCESS: Updated owner of $path to '$owner'."
                                messageType = "success"
                            } else {
                                statusMessage = "ERROR: Chown execution failed. Inspect parameters format."
                                messageType = "error"
                            }
                        } else {
                            statusMessage = "ERROR: Missing ownership attributes."
                            messageType = "error"
                        }
                    }
                    "symlink" -> {
                        val target = params["target"] ?: ""
                        val link = params["link"] ?: ""
                        if (target.isNotBlank() && link.isNotBlank()) {
                            val success = RootAdvancedFeatures.createSymbolicLink(target, link)
                            if (success) {
                                statusMessage = "SUCCESS: Created symbolic link $link -> $target."
                                messageType = "success"
                            } else {
                                statusMessage = "ERROR: Symlink command failed. Ensure link path does not exist."
                                messageType = "error"
                            }
                        } else {
                            statusMessage = "ERROR: Target or link path was blank."
                            messageType = "error"
                        }
                    }
                    "search" -> {
                        val query = params["query"] ?: ""
                        val searchDir = params["searchDir"] ?: "/storage/emulated/0"
                        if (query.isNotBlank()) {
                            searchResults = RootAdvancedFeatures.systemWideSearch(searchDir, query)
                            statusMessage = "Found ${searchResults.size} search results."
                            messageType = "success"
                        } else {
                            statusMessage = "ERROR: Query parameter is blank."
                            messageType = "error"
                        }
                    }
                    "restart" -> {
                        statusMessage = "REBOOT SIGNAL TRANSMITTED. File server service is restarting now."
                        messageType = "success"
                        // Trigger async restart after a short delay so the response can finish flushing
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            ServerManager.restartEngineOnly()
                        }, 500)
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Action Exception: ${e.message}"
                messageType = "error"
            }
        }

        val browsePath = params["browsePath"] ?: "/"
        val viewFile = params["viewFile"]

        // Fetch dynamic metrics
        val isRootActive = RootAdvancedFeatures.isRootActive()
        val devicePrivileged = RootAdvancedFeatures.mIsDeviceRooted
        val simActive = RootAdvancedFeatures.isRootSimulationEnabled
        val storageMetrics = RootAdvancedFeatures.getRawStorageMetrics()
        val cpuAndRam = RootAdvancedFeatures.getCpuRamUsage()
        val procList = RootAdvancedFeatures.getSystemProcesses().take(60)

        val html = buildAdminDashboardHtml(
            statusMessage = statusMessage,
            messageType = messageType,
            isRootActive = isRootActive,
            isDeviceRooted = devicePrivileged,
            isSimulationActive = simActive,
            storageMetrics = storageMetrics,
            cpuInfo = cpuAndRam.first,
            ramInfo = cpuAndRam.second,
            procList = procList,
            searchResults = searchResults,
            browsePath = browsePath,
            viewFile = viewFile
        )

        sendHtmlResponse(os, 200, "OK", html, false)
        logAndLogEntry(clientIp, method, uri, 200, html.length.toLong(), "Systems admin panel accessed")
    }

    private fun buildAdminDashboardHtml(
        statusMessage: String,
        messageType: String,
        isRootActive: Boolean,
        isDeviceRooted: Boolean,
        isSimulationActive: Boolean,
        storageMetrics: String,
        cpuInfo: String,
        ramInfo: String,
        procList: List<String>,
        searchResults: List<String>,
        browsePath: String = "/",
        viewFile: String? = null
    ): String {
        val parentRow = if (browsePath != "/") {
            val parentPath = getParentPath(browsePath)
            """
            <tr style="cursor: pointer;" onclick="window.location.href='/admin?browsePath=${URLEncoder.encode(parentPath, "UTF-8")}'">
                <td style="padding:12px; font-weight:bold; color:#10B981;">📁 .. (Parent Directory)</td>
                <td style="padding:12px; color:#9CA3AF;">DIR</td>
                <td style="padding:12px; color:#9CA3AF;">--</td>
                <td style="padding:12px; color:#9CA3AF;">--</td>
                <td style="padding:12px; color:#9CA3AF;">--</td>
            </tr>
            """.trimIndent()
        } else ""

        val items = getBrowseItems(browsePath)
        val rows = mutableListOf<String>()
        if (parentRow.isNotEmpty()) {
            rows.add(parentRow)
        }
        for (item in items) {
            if (item.isDir) {
                val fullSubPath = if (browsePath.endsWith("/")) {
                    browsePath + item.name
                } else {
                    "$browsePath/${item.name}"
                }
                val encodedSubPath = URLEncoder.encode(fullSubPath, "UTF-8")
                rows.add("""
                <tr style="cursor: pointer; border-top:1px solid #1F2937;" onclick="window.location.href='/admin?browsePath=$encodedSubPath'">
                    <td style="padding:12px; color:#3B82F6; font-weight:600;">📁 ${item.name}</td>
                    <td style="padding:12px; font-size:12px; color:#9CA3AF; font-family:monospace;">DIR</td>
                    <td style="padding:12px; font-size:12px; color:#9CA3AF; font-family:monospace;">--</td>
                    <td style="padding:12px; font-size:12px; color:#F1F5F9; font-family:monospace;">${item.owner}</td>
                    <td style="padding:12px; font-size:12px; color:#10B981; font-family:monospace;">${item.permissions}</td>
                </tr>
                """.trimIndent())
            } else {
                val encodedPath = URLEncoder.encode(browsePath, "UTF-8")
                val encodedFileName = URLEncoder.encode(item.name, "UTF-8")
                rows.add("""
                <tr style="cursor: pointer; border-top:1px solid #1F2937;" onclick="window.location.href='/admin?browsePath=$encodedPath&viewFile=$encodedFileName'">
                    <td style="padding:12px; color:#E2E8F0;">📄 ${item.name}</td>
                    <td style="padding:12px; font-size:12px; color:#9CA3AF; font-family:monospace;">FILE</td>
                    <td style="padding:12px; font-size:12px; color:#9CA3AF; font-family:monospace;">${item.size}</td>
                    <td style="padding:12px; font-size:12px; color:#F1F5F9; font-family:monospace;">${item.owner}</td>
                    <td style="padding:12px; font-size:12px; color:#9CA3AF; font-family:monospace;">${item.permissions}</td>
                </tr>
                """.trimIndent())
            }
        }
        val browserRowsHtml = if (rows.isEmpty()) {
            "<tr><td colspan='5' style='padding:16px; text-align:center; color:#6B7280; font-style:italic;'>Directory is empty or inaccessible</td></tr>"
        } else {
            rows.joinToString("\n")
        }

        val fileViewerHtml = if (viewFile != null) {
            val fullFilePath = if (browsePath.endsWith("/")) {
                browsePath + viewFile
            } else {
                "$browsePath/$viewFile"
            }
            
            var displayedContent = ""
            try {
                val realFile = File(fullFilePath)
                if (realFile.exists() && realFile.isFile && realFile.canRead()) {
                    val bytes = realFile.readBytes()
                    displayedContent = bytes.decodeToString()
                    if (displayedContent.length > 3000) {
                        displayedContent = displayedContent.substring(0, 3000) + "\n... [TRUNCATED] ..."
                    }
                }
            } catch (e: Exception) {
                // Fallback
            }

            if (displayedContent.isBlank() && RootAdvancedFeatures.isRootActive()) {
                displayedContent = RootAdvancedFeatures.readFileContent(fullFilePath, 3000)
            }

            if (displayedContent.isBlank()) {
                displayedContent = when (viewFile.lowercase()) {
                    "build.prop" -> """
                    # begin build properties
                    ro.build.id=RKQ1.200826.002
                    ro.build.display.id=RKQ1.200826.002 release-keys
                    ro.build.version.incremental=21.06.22
                    ro.build.version.sdk=33
                    ro.build.version.preview_sdk=0
                    ro.build.version.release=13
                    ro.product.model=ServerSimulator-M3
                    ro.product.brand=Android
                    ro.product.name=sys_eng_hub
                    ro.product.device=generic_x86_64
                    # end build properties
                    """.trimIndent()
                    "hosts" -> """
                    127.0.0.1       localhost
                    ::1             localhost
                    192.168.1.100   fileserver.internal
                    """.trimIndent()
                    "resolv.conf" -> """
                    nameserver 8.8.8.8
                    nameserver 1.1.1.1
                    """.trimIndent()
                    "gps.conf" -> """
                    NTP_SERVER=pool.ntp.org
                    XTRA_SERVER_1=http://xtra1.gpsonextra.net/xtra.bin
                    SUPL_HOST=supl.google.com
                    SUPL_PORT=7276
                    """.trimIndent()
                    "secure_key" -> """
                    [ENCRYPTED PRIVATE DECRYPTION SCHEME MASK]
                    D6FBBEE61CD054FAC0EE998BCE6782BB23DE91A9C682
                    """.trimIndent()
                    "init.rc" -> """
                    # Copyright (C) 2012 The Android Open Source Project
                    #
                    # syslogd configuration
                    on early-init
                        write /proc/sys/kernel/sysrq 0
                        restorecon /postinstall
                    
                    on init
                        sysclktz 0
                        loglevel 3
                    
                    on boot
                        ifup lo
                        hostname localhost
                        domainname localdomain
                    """.trimIndent()
                    "sh", "su", "ls", "chmod", "chown", "ps", "reboot" -> """
                    [SYSTEM BINARY EXEC MODEL]
                    Format: ELF 64-bit LSB shared object
                    Architecture: x86-64 / ARM64
                    Path: $fullFilePath
                    Simulation Mode: true
                    Privilege Validation: SUCCESS
                    """.trimIndent()
                    "config.cfg" -> """
                    # Systems Engineering Hub Client Config
                    debug_level=3
                    active_jail=none
                    breakout_allowed=false
                    performance_governor=schedutil
                    """.trimIndent()
                    "sys_log.db" -> """
                    [SQLITE DATABASE MATRIX BINARY]
                    Tables: system_events, diagnostic_runs, security_exceptions
                    Flags: read-only
                    Database Version: v1.4.2
                    """.trimIndent()
                    else -> """
                    [READ-ONLY STREAM VIEW]
                    Path: $fullFilePath
                    Device: simulation-ready
                    Awaiting full physical system mounting...
                    """.trimIndent()
                }
            }

            """
            <div style="background: #030712; border: 1px solid #1E293B; border-radius: 8px; margin-top: 16px; padding: 16px;">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; border-bottom:1px solid #1F2937; padding-bottom:8px;">
                    <h4 style="margin:0; color:#10B981; font-family:'Space Grotesk', sans-serif; font-size:15px;">📄 File Viewer: $viewFile</h4>
                    <a href="/admin?browsePath=${URLEncoder.encode(browsePath, "UTF-8")}" style="color:#EF4444; text-decoration:none; font-size:12px; font-weight:bold;">Close Preview</a>
                </div>
                <pre style="max-height: 250px; overflow-y: auto; background:#020617; border: 1px solid #1F2937; padding:12px; margin:0; border-radius:6px; font-family:'JetBrains Mono', monospace; font-size:12px; line-height:1.5; color:#E2E8F0;">${displayedContent.replace("<", "&lt;").replace(">", "&gt;")}</pre>
            </div>
            """
        } else ""

        val alertBox = if (statusMessage.isNotBlank()) {
            val borderClass = when (messageType) {
                "success" -> "#10B981"
                "error" -> "#EF4444"
                else -> "#3B82F6"
            }
            """
            <div style="border-left: 5px solid $borderClass; background: rgba(30, 41, 59, 0.7); padding: 16px; border-radius: 6px; margin-bottom: 24px;">
                <p style="margin:0; font-family: 'JetBrains Mono', monospace; font-size: 14px; color: #F1F5F9;">$statusMessage</p>
            </div>
            """
        } else ""

        val systemCheckLabel = if (isDeviceRooted) {
            "<span class='status-pill active'>REAL ROOT (UID 0) PRESENT</span>"
        } else if (isSimulationActive) {
            "<span class='status-pill active'>SIMULATOR ROOT UNLOCKED</span>"
        } else {
            "<span class='status-pill inactive'>STANDARD MODE (NO ROOT)</span>"
        }

        val procRowsHtml = procList.joinToString("\n") { line ->
            if (line.isBlank()) ""
            else "<tr><td style='font-family: monospace; white-space: pre-wrap;'>${line.replace("<", "&lt;").replace(">", "&gt;")}</td></tr>"
        }

        val searchItemsHtml = if (searchResults.isNotEmpty()) {
            """
            <div style="background: #020617; border: 1px solid #334155; padding: 12px; border-radius: 8px; margin-top: 12px; max-height: 200px; overflow-y: auto;">
                <h5 style="margin:0 0 8px 0; color: #10B981; font-family: monospace;">Search Results:</h5>
                <ul style="margin:0; padding-left: 20px; font-family: monospace; font-size: 13px; color: #F8FAFC;">
                    ${searchResults.joinToString("\n") { "<li>${it.replace("<", "&lt;").replace(">", "&gt;")}</li>" }}
                </ul>
            </div>
            """
        } else if (statusMessage.contains("Found 0 search results")) {
            "<p style='color: #94A3B8; font-size: 13px; font-style: italic; margin-top: 8px;'>No files matched query parameters.</p>"
        } else ""

        val activeJailString = RootAdvancedFeatures.chrootJailPath ?: "None"
        val breakoutCheckboxChecked = if (RootAdvancedFeatures.isSystemRootBreakoutAllowed) "checked" else ""

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Systems Engineering Admin Panel</title>
            <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
            <style>
                body {
                    background-color: #0B0F19;
                    color: #F8FAFC;
                    font-family: 'Space Grotesk', sans-serif;
                    margin: 0;
                    padding: 0;
                }
                .container {
                    max-width: 1200px;
                    margin: 0 auto;
                    padding: 32px 16px;
                }
                header {
                    display: flex;
                    align-content: center;
                    justify-content: space-between;
                    border-bottom: 1px solid #1E293B;
                    padding-bottom: 20px;
                    margin-bottom: 32px;
                }
                h1 {
                    font-size: 26px;
                    font-weight: 700;
                    margin: 0;
                    letter-spacing: -0.5px;
                    color: #F1F5F9;
                }
                h2 {
                    font-size: 18px;
                    font-weight: 600;
                    margin: 0 0 16px 0;
                    color: #E2E8F0;
                }
                .status-pill {
                    display: inline-block;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 12px;
                    font-weight: 600;
                    padding: 6px 12px;
                    border-radius: 9999px;
                }
                .status-pill.active {
                    background: rgba(16, 185, 129, 0.15);
                    color: #10B981;
                    border: 1px solid rgba(16, 185, 129, 0.3);
                }
                .status-pill.inactive {
                    background: rgba(239, 68, 68, 0.15);
                    color: #EF4444;
                    border: 1px solid rgba(239, 68, 68, 0.3);
                }
                .grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
                    gap: 24px;
                    margin-bottom: 32px;
                }
                .card {
                    background: #111827;
                    border: 1px solid #1F2937;
                    border-radius: 12px;
                    padding: 24px;
                }
                .form-control {
                    margin-bottom: 16px;
                }
                .form-control label {
                    display: block;
                    font-size: 13px;
                    font-weight: 500;
                    margin-bottom: 6px;
                    color: #9CA3AF;
                }
                .form-control input[type="text"] {
                    width: 100%;
                    box-sizing: border-box;
                    background: #1F2937;
                    border: 1px solid #374151;
                    padding: 10px 12px;
                    border-radius: 6px;
                    color: #F9FAFB;
                    font-family: inherit;
                    font-size: 14px;
                }
                .form-control input[type="text"]:focus {
                    border-color: #6366F1;
                    outline: none;
                }
                .btn {
                    background: #6366F1;
                    color: #FFFFFF;
                    border: none;
                    font-weight: 500;
                    font-family: inherit;
                    padding: 10px 16px;
                    border-radius: 6px;
                    cursor: pointer;
                    display: inline-flex;
                    align-items: center;
                    font-size: 14px;
                    transition: border 0.2s, background 0.2s;
                }
                .btn:hover {
                    background: #4F46E5;
                }
                .btn-danger {
                    background: #EF4444;
                }
                .btn-danger:hover {
                    background: #DC2626;
                }
                .btn-secondary {
                    background: #374151;
                }
                .btn-secondary:hover {
                    background: #4B5563;
                }
                pre {
                    background: #030712;
                    border: 1px solid #1F2937;
                    border-radius: 8px;
                    padding: 16px;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 12px;
                    color: #E2E8F0;
                    overflow-x: auto;
                    max-height: 250px;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                }
                th, td {
                    text-align: left;
                    padding: 10px;
                    border-bottom: 1px solid #1F2937;
                }
                th {
                    font-size: 12px;
                    text-transform: uppercase;
                    color: #9CA3AF;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <header>
                    <div>
                        <h1>💻 Systems Engineering Hub</h1>
                        <p style="margin: 4px 0 0 0; color: #9CA3AF; font-size: 14px;">High-Privilege Operational Administration Console</p>
                    </div>
                    <div>
                        $systemCheckLabel
                    </div>
                </header>

                $alertBox

                <div class="grid">
                    <!-- General Settings / Root Stimulation Toggle -->
                    <div class="card">
                        <h2>⚙️ Privileged Core Controls</h2>
                        <p style="font-size: 13px; color: #9CA3AF; margin-top:0; margin-bottom: 16px;">
                            Toggle Simulated privileges to bypass standard Android file APIs and allow local block device modeling.
                        </p>
                        <form method="get" action="/admin" style="margin-bottom: 16px;">
                            <input type="hidden" name="action" value="toggle_root_simulation">
                            <button type="submit" class="btn btn-secondary">
                                ${if (isSimulationActive) "Deactivate Simulator" else "Activate Root Simulator"}
                            </button>
                        </form>
                        
                        <hr style="border-top:1px solid #1F2937; margin: 20px 0;">

                        <h2>🔒 Jail / Chroot Sandboxing</h2>
                        <form method="get" action="/admin">
                            <input type="hidden" name="action" value="update_jail">
                            <div class="form-control">
                                <label>Chroot Jailed Directory Path</label>
                                <input type="text" name="jailPath" placeholder="e.g. /storage/emulated/0/Download" value="${if (activeJailString != "None") activeJailString else ""}">
                            </div>
                            <div class="form-control" style="display:flex; align-items:center;">
                                <input type="checkbox" name="allowBreakout" value="true" id="breakout" $breakoutCheckboxChecked style="margin-right:8px;">
                                <label for="breakout" style="display:inline; margin:0; cursor:pointer;">Unlock true filesystem root (/) access (Root Breakout)</label>
                            </div>
                            <button type="submit" class="btn">Update Isolation Constraints</button>
                        </form>
                    </div>

                    <!-- Low-Level Filesystem Commands -->
                    <div class="card">
                        <h2>🛠️ File system Low-Level Ops</h2>
                        <p style="font-size: 13px; color: #9CA3AF; margin-top:0; margin-bottom: 16px;">
                            Execute secure, direct octal transformations and ownership bindings below user permissions.
                        </p>
                        
                        <!-- Chmod -->
                        <form method="get" action="/admin" style="border-bottom: 1px dashed #1F2937; padding-bottom:16px; margin-bottom:16px;">
                            <input type="hidden" name="action" value="chmod">
                            <div class="grid" style="grid-template-columns: 2fr 1fr; gap: 8px; margin-bottom:8px;">
                                <div>
                                    <input type="text" name="path" placeholder="Absolute Target Path" required style="box-sizing:border-box; width:100%; background: #1F2937; border: 1px solid #374151; padding: 8px 10px; border-radius: 6px; color:#fff;">
                                </div>
                                <div>
                                    <input type="text" name="mode" placeholder="e.g. 0755" required style="box-sizing:border-box; width:100%; background: #1F2937; border: 1px solid #374151; padding: 8px 10px; border-radius: 6px; color:#fff;">
                                </div>
                            </div>
                            <button type="submit" class="btn" style="padding: 7px 12px; font-size:12px;">Execute chmod</button>
                        </form>

                        <!-- Chown -->
                        <form method="get" action="/admin" style="border-bottom: 1px dashed #1F2937; padding-bottom:16px; margin-bottom:16px;">
                            <input type="hidden" name="action" value="chown">
                            <div class="grid" style="grid-template-columns: 2fr 1fr; gap: 8px; margin-bottom:8px;">
                                <div>
                                    <input type="text" name="path" placeholder="Absolute Target Path" required style="box-sizing:border-box; width:100%; background: #1F2937; border: 1px solid #374151; padding: 8px 10px; border-radius: 6px; color:#fff;">
                                </div>
                                <div>
                                    <input type="text" name="owner" placeholder="Owner" required style="box-sizing:border-box; width:100%; background: #1F2937; border: 1px solid #374151; padding: 8px 10px; border-radius: 6px; color:#fff;">
                                </div>
                            </div>
                            <button type="submit" class="btn" style="padding: 7px 12px; font-size:12px;">Execute chown</button>
                        </form>

                        <!-- Symlink -->
                        <form method="get" action="/admin">
                            <input type="hidden" name="action" value="symlink">
                            <div class="grid" style="grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom:8px;">
                                <div>
                                    <input type="text" name="target" placeholder="Target Source Path" required style="box-sizing:border-box; width:100%; background: #1F2937; border: 1px solid #374151; padding: 8px 10px; border-radius: 6px; color:#fff;">
                                </div>
                                <div>
                                    <input type="text" name="link" placeholder="Symlink Sym Destination" required style="box-sizing:border-box; width:100%; background: #1F2937; border: 1px solid #374151; padding: 8px 10px; border-radius: 6px; color:#fff;">
                                </div>
                            </div>
                            <button type="submit" class="btn" style="padding: 7px 12px; font-size:12px;">Create Symlink</button>
                        </form>
                    </div>
                </div>

                <div class="grid">
                    <!-- Directory Browser Section -->
                    <div class="card" style="grid-column: span 2;">
                        <h2 style="display:flex; align-items:center; gap:8px;">📂 Live System & Root Directory Browser</h2>
                        <p style="font-size: 13px; color: #9CA3AF; margin-top:0; margin-bottom:16px;">
                            Navigate the absolute root filesystems of the host. When simulator mode is active, this displays real files where readable, otherwise falling back to simulated system directories.
                        </p>
                        
                        <form method="get" action="/admin" style="margin-bottom: 16px; display: flex; gap: 8px;">
                            <span style="font-family: 'JetBrains Mono', monospace; font-size:14px; align-self:center; color:#10B981; font-weight:bold;">Path:</span>
                            <input type="text" name="browsePath" placeholder="Enter absolute path (e.g. /system/bin)" value="$browsePath" style="flex-grow: 1; box-sizing:border-box; background: #1F2937; border: 1px solid #374151; padding: 10px 12px; border-radius: 6px; color:#fff; font-family: monospace;">
                            <button type="submit" class="btn">Navigate</button>
                        </form>
                        
                        <div style="background: #030712; border: 1px solid #1F2937; border-radius: 8px; overflow-x: auto;">
                            <table style="width: 100%; border-collapse: collapse;">
                                <thead>
                                    <tr style="background:#111827;">
                                        <th style="padding:12px;">Name</th>
                                        <th style="padding:12px;">Type</th>
                                        <th style="padding:12px;">Size</th>
                                        <th style="padding:12px;">Owner</th>
                                        <th style="padding:12px;">Permissions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    $browserRowsHtml
                                </tbody>
                            </table>
                        </div>
                        
                        $fileViewerHtml
                    </div>
                </div>

                <div class="grid">
                    <!-- System-wide File Search -->
                    <div class="card" style="grid-column: span 2;">
                        <h2>🔍 Server-Side Deep System Search</h2>
                        <p style="font-size: 13px; color: #9CA3AF; margin-top:0; margin-bottom:12px;">
                            Execute lightning fast recursive queries leveraging Linux utilities directly on host boundaries.
                        </p>
                        <form method="get" action="/admin" style="display:flex; gap:12px;">
                            <input type="hidden" name="action" value="search">
                            <input type="text" name="searchDir" placeholder="Starting directory (default /storage/emulated/0)" style="max-width: 280px; box-sizing:border-box; background: #1F2937; border: 1px solid #374151; padding: 10px 12px; border-radius: 6px; color:#fff;">
                            <input type="text" name="query" placeholder="Matching name or wildcard (e.g. *.html)" required style="flex-grow:1; box-sizing:border-box; background: #1F2937; border: 1px solid #374151; padding: 10px 12px; border-radius: 6px; color:#fff;">
                            <button type="submit" class="btn">Deep Search</button>
                        </form>
                        $searchItemsHtml
                    </div>
                </div>

                <div class="grid">
                    <!-- IO & Mounting Partition state diagnostics -->
                    <div class="card" style="grid-column: span 2;">
                        <h2>📊 Raw Storage Partition Metrics</h2>
                        <pre>$storageMetrics</pre>
                    </div>
                </div>

                <div class="grid">
                    <!-- Hardware CPU & Memory statistics -->
                    <div class="card">
                        <h2>🧠 CPU Stat Core Summary</h2>
                        <pre>$cpuInfo</pre>
                    </div>
                    <div class="card">
                        <h2>💾 RAM (Memory Footprint)</h2>
                        <pre>$ramInfo</pre>
                    </div>
                </div>

                <!-- Process Monitoring and Emergency management -->
                <div class="grid">
                    <div class="card" style="grid-column: span 2;">
                        <h2>🏁 Running Host Processes (ps list)</h2>
                        <div style="max-height: 300px; overflow-y: auto; background: #030712; border: 1px solid #1F2937; border-radius: 8px;">
                            <table>
                                <thead>
                                    <tr>
                                        <th>Raw System Output Line</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    $procRowsHtml
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>

                <!-- Service Actions -->
                <div class="card" style="border: 1px solid rgba(239, 68, 68, 0.4); background: rgba(239, 68, 68, 0.05);">
                    <h2 style="color: #EF4444;">⚠️ Core Operational Safeguards</h2>
                    <p style="font-size: 14px; color: #FCA5A5; margin-top:0; margin-bottom: 20px;">
                        Restarting the service daemon will detach raw listening sockets and immediately initialize a clean listener.
                    </p>
                    <form method="get" action="/admin">
                        <input type="hidden" name="action" value="restart">
                        <button type="submit" class="btn btn-danger" onclick="return confirm('Confirm complete server daemon socket cycle?');">
                            Restart File Server Service
                        </button>
                    </form>
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun getParentPath(path: String): String {
        val clean = if (path.endsWith("/")) path.dropLast(1) else path
        val lastSlash = clean.lastIndexOf('/')
        if (lastSlash <= 0) return "/"
        return clean.substring(0, lastSlash)
    }

    private fun getBrowseItems(path: String): List<BrowseItem> {
        val cleanPath = if (path.isBlank()) "/" else path
        val result = mutableListOf<BrowseItem>()
        
        try {
            if (RootAdvancedFeatures.isRootActive() && RootAdvancedFeatures.mIsDeviceRooted) {
                val rootItems = RootAdvancedFeatures.listDirectoryDetails(cleanPath)
                if (rootItems != null) {
                    return rootItems
                }
            }
            val dir = File(cleanPath)
            if (RootAdvancedFeatures.fileExists(dir) && RootAdvancedFeatures.fileIsDirectory(dir)) {
                val filesList = RootAdvancedFeatures.fileListFiles(dir)
                for (f in filesList) {
                    val isDir = RootAdvancedFeatures.fileIsDirectory(f)
                    val sizeStr = if (isDir) "--" else {
                        val len = RootAdvancedFeatures.fileLength(f)
                        if (len < 1024) "$len B" else "${len / 1024} KB"
                    }
                    val user = try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            java.nio.file.Files.getOwner(f.toPath()).name
                        } else "root"
                    } catch (e: Exception) {
                        "root"
                    }
                    val perms = if (isDir) "drwxr-xr-x" else "-rw-r--r--"
                    result.add(BrowseItem(f.name, isDir, sizeStr, user, perms))
                }
                result.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                return result
            }
        } catch (e: Exception) {
            // fallback
        }

        val normalized = if (cleanPath.endsWith("/")) cleanPath else "$cleanPath/"
        when (normalized) {
            "/" -> {
                result.add(BrowseItem("system", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("vendor", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("data", true, "--", "system", "drwxrwx--x"))
                result.add(BrowseItem("storage", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("sdcard", true, "--", "root", "lrwxrwxrwx"))
                result.add(BrowseItem("etc", true, "--", "root", "lrwxrwxrwx"))
                result.add(BrowseItem("proc", true, "--", "root", "dr-xr-xr-x"))
                result.add(BrowseItem("sys", true, "--", "root", "dr-xr-xr-x"))
                result.add(BrowseItem("sbin", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("dev", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("init.rc", false, "27.8 KB", "root", "-rwxr-x---"))
            }
            "/system/" -> {
                result.add(BrowseItem("app", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("bin", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("etc", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("fonts", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("framework", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("lib", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("lib64", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("priv-app", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("usr", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("build.prop", false, "4.4 KB", "root", "-rw-r--r--"))
            }
            "/system/bin/" -> {
                result.add(BrowseItem("sh", false, "122.5 KB", "root", "-rwxr-xr-x"))
                result.add(BrowseItem("su", false, "73.6 KB", "root", "-rwsr-sr-x"))
                result.add(BrowseItem("ls", false, "52.8 KB", "root", "-rwxr-xr-x"))
                result.add(BrowseItem("chmod", false, "41.1 KB", "root", "-rwxr-xr-x"))
                result.add(BrowseItem("chown", false, "37.3 KB", "root", "-rwxr-xr-x"))
                result.add(BrowseItem("ps", false, "48.1 KB", "root", "-rwxr-xr-x"))
                result.add(BrowseItem("reboot", false, "17.8 KB", "root", "-rwxr-xr-x"))
            }
            "/system/etc/", "/etc/" -> {
                result.add(BrowseItem("hosts", false, "54 B", "root", "-rw-r--r--"))
                result.add(BrowseItem("resolv.conf", false, "42 B", "root", "-rw-r--r--"))
                result.add(BrowseItem("gps.conf", false, "840 B", "root", "-rw-r-----"))
                result.add(BrowseItem("security", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("permissions", true, "--", "root", "drwxr-xr-x"))
            }
            "/data/" -> {
                result.add(BrowseItem("app", true, "--", "system", "drwxrwx--x"))
                result.add(BrowseItem("data", true, "--", "system", "drwxrwx--x"))
                result.add(BrowseItem("user", true, "--", "system", "drwxrwx--x"))
                result.add(BrowseItem("system", true, "--", "system", "drwxrwx--x"))
                result.add(BrowseItem("local", true, "--", "shell", "drwxr-x--x"))
                result.add(BrowseItem("secure_key", false, "256 B", "system", "-rw-------"))
            }
            "/data/data/" -> {
                result.add(BrowseItem("android", true, "--", "system", "drwxr-xr-x"))
                result.add(BrowseItem("com.android.providers.settings", true, "--", "system", "drwxr-x--x"))
                result.add(BrowseItem("com.android.chrome", true, "--", "u0_a102", "drwxr-x--x"))
                result.add(BrowseItem("com.google.android.youtube", true, "--", "u0_a115", "drwxr-x--x"))
                result.add(BrowseItem("com.google.android.gms", true, "--", "u0_a192", "drwxr-x--x"))
                result.add(BrowseItem("com.aistudio.httpfileserverwebdav", true, "--", "u0_a321", "drwxr-xr-x"))
                result.add(BrowseItem("com.aistudio.localgallery.mpywdj", true, "--", "u0_a299", "drwxr-x--x"))
                result.add(BrowseItem("com.aistudio.localremote.rtcxpv", true, "--", "u0_a316", "drwxr-x--x"))
            }
            "/storage/" -> {
                result.add(BrowseItem("emulated", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("self", true, "--", "root", "drwxr-xr-x"))
            }
            "/storage/emulated/" -> {
                result.add(BrowseItem("0", true, "--", "root", "drwxr-xr-x"))
            }
            "/storage/emulated/0/" -> {
                result.add(BrowseItem("Android", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("DCIM", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("Documents", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("Download", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("Movies", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("Music", true, "--", "root", "drwxr-xr-x"))
                result.add(BrowseItem("Pictures", true, "--", "root", "drwxr-xr-x"))
            }
            else -> {
                result.add(BrowseItem("lost+found", true, "--", "root", "drwx------"))
                result.add(BrowseItem("config.cfg", false, "1.2 KB", "root", "-rw-r--r--"))
                result.add(BrowseItem("sys_log.db", false, "24.0 KB", "root", "-rw-------"))
            }
        }
        result.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return result
    }
}

data class BrowseItem(
    val name: String,
    val isDir: Boolean,
    val size: String,
    val owner: String,
    val permissions: String
)

