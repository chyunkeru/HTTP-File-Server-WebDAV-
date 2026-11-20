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

            if (isAdminRoute) {
                sendRedirect(outputStream, "/")
                return
            }

            // Check Basic Authentication
            if (!isAnonymousAllowed) {
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

            // Route request
            val resolvedResult = resolvePath(decodedUri)

            if (resolvedResult == null) {
                val isRootUri = decodedUri == "/" || decodedUri.isEmpty()
                if (isRootUri) {
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
                } else {
                    sendSimpleResponse(outputStream, 404, "Not Found", "File or folder not found")
                    logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "Path not found: $decodedUri")
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
                    val xml = if (!RootAdvancedFeatures.fileExists(targetFile)) {
                        sendSimpleResponse(outputStream, 404, "Not Found", "Resource not found")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "File not found for PROPFIND")
                        return
                    } else if (RootAdvancedFeatures.fileIsDirectory(targetFile)) {
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
                    if (!RootAdvancedFeatures.fileExists(targetFile)) {
                        sendSimpleResponse(outputStream, 404, "Not Found", "Source file doesn't exist")
                        logAndLogEntry(clientIp, requestMethod, requestUri, 404, 0L, "Source physical file missing")
                    } else {
                        val parent = destFile.parentFile
                        if (parent != null && !RootAdvancedFeatures.fileExists(parent)) {
                            RootAdvancedFeatures.createDir(parent)
                        }

                        val success = if (requestMethod == "MOVE") {
                            RootAdvancedFeatures.moveFileWithRoot(targetFile, destFile)
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
        val matchingFolder = activeFolders.find { it.name.trim().equals(firstSegment.trim(), ignoreCase = true) } ?: return null

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
        val rootDetails = if (RootAdvancedFeatures.isRootActive() && RootAdvancedFeatures.mIsDeviceRooted) {
            RootAdvancedFeatures.listDirectoryDetails(parentDir.absolutePath)
        } else null

        val items: List<FolderRowItem> = if (rootDetails != null) {
            rootDetails.map {
                val size = if (it.isDir) "-" else it.size
                val type = if (it.isDir) "Folder" else getFileExtension(it.name).uppercase()
                FolderRowItem(it.name, it.isDir, size, type)
            }
        } else {
            val filesList = RootAdvancedFeatures.fileListFiles(parentDir)
            filesList.sortedWith(compareBy({ !RootAdvancedFeatures.fileIsDirectory(it) }, { it.name.lowercase() }))
                .map { file ->
                    val isDir = RootAdvancedFeatures.fileIsDirectory(file)
                    val sizeStr = if (isDir) "-" else formatFileSize(RootAdvancedFeatures.fileLength(file))
                    val typeStr = if (isDir) "Folder" else getFileExtension(file.name).uppercase()
                    FolderRowItem(file.name, isDir, sizeStr, typeStr)
                }
        }

        val freeBytes = try { parentDir.freeSpace } catch (e: Exception) { 0L }
        val storageBadge = if (freeBytes > 0) "${formatFileSize(freeBytes)} Free" else "Active Share"

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
                <td class="name-col" colspan="4">
                    <svg class="icon icon-up" viewBox="0 0 24 24"><path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"/></svg>
                    <a href="${parentPath}">.. (Up a level)</a>
                </td>
            </tr>
            """.trimIndent()
        } else {
            ""
        }

        val entriesHtml = items.joinToString("") { item ->
            val relativeUrl = if (requestUri.endsWith("/")) {
                requestUri + URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            } else {
                requestUri + "/" + URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            }

            val iconD = if (item.isDir) {
                "M10 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2h-8l-2-2z"
            } else {
                "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"
            }

            val isEditable = !item.isDir && isTextEditable(item.name)
            val editBtn = if (isEditable && isWritable) {
                """<button class="btn-action btn-edit" onclick="openFileEditor('${relativeUrl}', '${WebDavHandler.escapeXml(item.name)}')">✏ Edit</button>"""
            } else if (isEditable) {
                """<button class="btn-action btn-edit" onclick="openFileEditor('${relativeUrl}', '${WebDavHandler.escapeXml(item.name)}')">👁 View</button>"""
            } else ""

            val deleteBtn = if (isWritable) {
                """<button class="btn-action btn-delete" onclick="deleteItem('${relativeUrl}', '${WebDavHandler.escapeXml(item.name)}')">🗑 Delete</button>"""
            } else ""

            """
            <tr>
                <td class="name-col">
                    <svg class="icon" viewBox="0 0 24 24"><path d="$iconD"/></svg>
                    <a href="${relativeUrl}${if (item.isDir) "/" else ""}">${WebDavHandler.escapeXml(item.name)}</a>
                </td>
                <td>${item.typeStr}</td>
                <td>${item.sizeStr}</td>
                <td style="text-align: right; white-space: nowrap;">
                    $editBtn$deleteBtn
                </td>
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
            <div class="card p-4 bg-yellow" style="background-color: #fef3c7; border: 1px solid #f59e0b; color: #b45309; border-radius: 8px; margin-bottom: 24px;">
                <p style="margin: 0; font-weight: bold;">⚠️ This shared directory is READ-ONLY. Creating files and folders is disabled.</p>
            </div>
            """.trimIndent()
        }

        return getThemePageTemplate(
            title = folderName,
            breadcrumbsHtml = breadcrumbsSb.toString(),
            bodyHtml = """
                $controlPanelHtml

                <div class="card p-4" style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:16px;">
                    <div style="display:flex; align-items:center; gap:8px; flex:1; max-width:400px;">
                        <input type="text" id="fileFilterInput" placeholder="🔍 Quick filter files..." onkeyup="filterFolderFiles()" class="form-input" style="width:100%; padding:8px 12px; border-radius:6px; border:1px solid var(--border-color);">
                    </div>
                    <div style="display:flex; align-items:center; gap:10px;">
                        <span style="font-size:13px; font-weight:600; color:var(--text-secondary); background:#f1f5f9; padding:6px 14px; border-radius:8px; border:1px solid var(--border-color);">
                            💾 $storageBadge
                        </span>
                        ${if (RootAdvancedFeatures.mIsDeviceRooted) """<span style="background:#dcfce7; color:#15803d; font-size:11px; font-weight:700; padding:4px 10px; border-radius:8px; border:1px solid #bbf7d0;">ROOT ACTIVE</span>""" else ""}
                    </div>
                </div>

                <div class="card overflow-hidden">
                    <table>
                        <thead>
                            <tr>
                                <th>Name</th>
                                <th style="width: 15%">Type</th>
                                <th style="width: 20%">Size</th>
                                <th style="width: 18%; text-align: right;">Action</th>
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

    private fun isTextEditable(fileName: String): Boolean {
        val ext = getFileExtension(fileName).lowercase()
        val textExtensions = setOf(
            "txt", "log", "prop", "xml", "json", "conf", "cfg", "rc", "ini",
            "sh", "yml", "yaml", "md", "csv", "properties", "html", "htm", "css", "js", "ts", "kt", "java"
        )
        val textExactNames = setOf("hosts", "resolv.conf", "init.rc", "build.prop")
        return textExtensions.contains(ext) || textExactNames.contains(fileName.lowercase())
    }

    private data class FolderRowItem(
        val name: String,
        val isDir: Boolean,
        val sizeStr: String,
        val typeStr: String
    )

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

                .btn-action {
                    display: inline-flex;
                    align-items: center;
                    gap: 4px;
                    padding: 5px 10px;
                    font-size: 12px;
                    font-weight: 600;
                    border-radius: 6px;
                    border: 1px solid var(--border-color);
                    background: var(--bg-page);
                    color: var(--text-primary);
                    cursor: pointer;
                    transition: all 0.15s ease;
                }
                .btn-action:hover { background: #e2e8f0; }
                .btn-edit {
                    background: #eff6ff;
                    color: #2563eb;
                    border-color: #bfdbfe;
                    margin-right: 4px;
                }
                .btn-edit:hover { background: #dbeafe; }
                .btn-delete {
                    background: #fef2f2;
                    border: 1px solid #fecaca;
                    cursor: pointer;
                    color: var(--danger);
                    padding: 5px 10px;
                    font-size: 12px;
                    font-weight: 600;
                    border-radius: 6px;
                }
                .btn-delete:hover { background: #fee2e2; }
                .icon-action { width: 18px; height: 18px; fill: currentColor; }

                .empty-state { text-align: center; color: var(--text-secondary); padding: 40px; }
                .empty-state td { padding: 40px; }

                /* Editor Modal */
                .modal-overlay {
                    display: none;
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100vw;
                    height: 100vh;
                    background: rgba(0, 0, 0, 0.6);
                    backdrop-filter: blur(4px);
                    z-index: 1000;
                    justify-content: center;
                    align-items: center;
                }
                .modal-overlay.active {
                    display: flex;
                }
                .modal-card {
                    background: var(--bg-card);
                    border: 1px solid var(--border-color);
                    border-radius: 12px;
                    width: 90vw;
                    max-width: 900px;
                    height: 85vh;
                    display: flex;
                    flex-direction: column;
                    box-shadow: 0 20px 25px -5px rgba(0,0,0,0.2), 0 10px 10px -5px rgba(0,0,0,0.1);
                    overflow: hidden;
                }
                .modal-header {
                    padding: 16px 20px;
                    border-bottom: 1px solid var(--border-color);
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    background: var(--bg-page);
                }
                .modal-title {
                    font-size: 16px;
                    font-weight: 600;
                    color: var(--text-primary);
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }
                .modal-body {
                    flex: 1;
                    padding: 16px;
                    display: flex;
                    flex-direction: column;
                    background: #0d1117;
                }
                .editor-textarea {
                    flex: 1;
                    width: 100%;
                    background: transparent;
                    color: #e6edf3;
                    border: none;
                    outline: none;
                    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, Courier, monospace;
                    font-size: 13px;
                    line-height: 1.5;
                    resize: none;
                    white-space: pre;
                    overflow: auto;
                    tab-size: 4;
                }
                .modal-footer {
                    padding: 12px 20px;
                    border-top: 1px solid var(--border-color);
                    display: flex;
                    justify-content: flex-end;
                    gap: 10px;
                    background: var(--bg-page);
                }
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

                // Real-time File Filter
                function filterFolderFiles() {
                    const input = document.getElementById("fileFilterInput");
                    const filter = input ? input.value.toLowerCase().trim() : "";
                    const rows = document.querySelectorAll(".item-row");
                    rows.forEach(row => {
                        const nameEl = row.querySelector(".name-col a");
                        if (!nameEl) return;
                        const name = nameEl.textContent.toLowerCase();
                        if (name.includes(filter)) {
                            row.style.display = "";
                        } else {
                            row.style.display = "none";
                        }
                    });
                }

                // File Editor Modal
                let currentEditingUrl = null;

                async function openFileEditor(url, fileName) {
                    currentEditingUrl = url;
                    const modal = document.getElementById("editorModal");
                    const title = document.getElementById("editorFileName");
                    const textarea = document.getElementById("editorTextarea");
                    const statusText = document.getElementById("editorStatus");
                    
                    title.textContent = fileName;
                    textarea.value = "Loading file content...";
                    textarea.disabled = true;
                    statusText.textContent = "";
                    modal.classList.add("active");

                    try {
                        const res = await fetch(url);
                        if (!res.ok) throw new Error("HTTP " + res.status + " " + res.statusText);
                        const text = await res.text();
                        textarea.value = text;
                        textarea.disabled = false;
                        textarea.focus();
                    } catch (err) {
                        textarea.value = "Failed to load file: " + err.message;
                        statusText.textContent = "Error loading";
                        statusText.style.color = "var(--danger)";
                    }
                }

                function closeFileEditor() {
                    const modal = document.getElementById("editorModal");
                    modal.classList.remove("active");
                    currentEditingUrl = null;
                }

                async function saveFileContent() {
                    if (!currentEditingUrl) return;
                    const textarea = document.getElementById("editorTextarea");
                    const saveBtn = document.getElementById("editorSaveBtn");
                    const statusText = document.getElementById("editorStatus");
                    const content = textarea.value;

                    saveBtn.disabled = true;
                    saveBtn.textContent = "Saving...";
                    statusText.textContent = "Writing file via WebDAV...";
                    statusText.style.color = "var(--primary)";

                    try {
                        const res = await fetch(currentEditingUrl, {
                            method: "PUT",
                            headers: { "Content-Type": "text/plain; charset=utf-8" },
                            body: content
                        });
                        if (res.ok || res.status === 201 || res.status === 204) {
                            statusText.textContent = "Saved successfully!";
                            statusText.style.color = "#10b981";
                            setTimeout(() => {
                                closeFileEditor();
                            }, 600);
                        } else {
                            throw new Error("HTTP " + res.status + " " + res.statusText);
                        }
                    } catch (err) {
                        alert("Failed to save: " + err.message);
                        statusText.textContent = "Save failed: " + err.message;
                        statusText.style.color = "var(--danger)";
                    } finally {
                        saveBtn.disabled = false;
                        saveBtn.textContent = "Save Changes";
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

            <!-- Editor Modal -->
            <div id="editorModal" class="modal-overlay">
                <div class="modal-card">
                    <div class="modal-header">
                        <div class="modal-title">
                            <svg style="width:20px;height:20px;fill:currentColor" viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>
                            <span id="editorFileName">File Editor</span>
                        </div>
                        <span id="editorStatus" style="font-size:12px;font-weight:500;margin-left:auto;margin-right:16px;"></span>
                        <button class="btn-action" onclick="closeFileEditor()" style="padding:4px 8px;">✕</button>
                    </div>
                    <div class="modal-body">
                        <textarea id="editorTextarea" class="editor-textarea" spellcheck="false"></textarea>
                    </div>
                    <div class="modal-footer">
                        <button class="btn-action" onclick="closeFileEditor()">Cancel</button>
                        <button id="editorSaveBtn" class="btn-action btn-edit" style="background:#2563eb;color:#ffffff;border:none;padding:6px 16px;" onclick="saveFileContent()">Save Changes</button>
                    </div>
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun sendRedirect(os: OutputStream, location: String) {
        val head = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        os.write(head.toByteArray(Charsets.UTF_8))
        os.flush()
    }
}

data class BrowseItem(
    val name: String,
    val isDir: Boolean,
    val size: String,
    val owner: String,
    val permissions: String
)

