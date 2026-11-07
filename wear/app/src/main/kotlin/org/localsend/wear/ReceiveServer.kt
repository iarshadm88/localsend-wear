package org.localsend.wear

import fi.iki.elonen.NanoHTTPD
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.serializer


/** What the UI must decide on. Return the accepted file IDs, or null to decline. */
interface ReceiveHandler {
    fun onRegister(peer: Peer)
    fun decide(sender: DeviceInfo, files: List<FileDto>): Set<String>?
    fun onMessage(sender: DeviceInfo, text: String)
    fun onFileReceived(sender: DeviceInfo, name: String, file: File)
}

/**
 * Plain-HTTP receiver for protocol v2 (register, info, prepare-upload, upload, cancel).
 *
 * HTTP, not HTTPS: the Android Keystore cannot hand out the private key the usual TLS server
 * setup needs, so this server announces `protocol: http`. Senders honour the announced protocol.
 * Only one upload session is active at a time, like the reference implementation.
 */
class ReceiveServer(
    port: Int,
    private val saveDir: File,
    private val selfInfo: () -> DeviceInfo,
    private val handler: ReceiveHandler,
) : NanoHTTPD(port) {

    private class Session(
        val id: String,
        val sender: DeviceInfo,
        val ip: String,
        val files: Map<String, FileDto>,
        val tokens: Map<String, String>,
    ) {
        val pending = tokens.keys.toMutableSet()
    }

    @Volatile private var session: Session? = null

    override fun serve(s: IHTTPSession): Response = try {
        val path = s.uri.removePrefix(Protocol.API)
        when {
            s.method == Method.POST && path == "/register" -> register(s)
            s.method == Method.GET && path == "/info" -> json(Response.Status.OK, selfJson())
            s.method == Method.POST && path == "/prepare-upload" -> prepareUpload(s)
            s.method == Method.POST && path == "/upload" -> upload(s)
            s.method == Method.POST && path == "/cancel" -> cancel(s)
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    } catch (e: Exception) {
        text(Response.Status.INTERNAL_ERROR, e.message ?: "error")
    }

    private fun selfJson() = Protocol.json.encodeToString(DeviceInfo.serializer(), selfInfo())

    private fun register(s: IHTTPSession): Response {
        val info = Protocol.json.decodeFromString<DeviceInfo>(String(readBody(s, 64 * 1024)))
        handler.onRegister(info.toPeer(s.remoteIpAddress))
        return json(Response.Status.OK, selfJson())
    }

    private fun prepareUpload(s: IHTTPSession): Response {
        val req = Protocol.json.decodeFromString<PrepareUploadRequest>(String(readBody(s, 1024 * 1024)))
        if (req.files.isEmpty()) return text(Response.Status.BAD_REQUEST, "No files")
        if (session != null) return text(Response.Status.CONFLICT, "Blocked by another session")

        // Text messages travel in `preview`: show them and answer 204 (nothing to upload).
        val single = req.files.values.singleOrNull()
        if (single?.preview != null && single.fileType.startsWith("text/")) {
            val accepted = handler.decide(req.info, listOf(single))
            if (accepted == null || single.id !in accepted) return text(Response.Status.FORBIDDEN, "Rejected")
            handler.onMessage(req.info, single.preview)
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        }

        val accepted = handler.decide(req.info, req.files.values.toList())
            ?: return text(Response.Status.FORBIDDEN, "Rejected")
        val ids = accepted.filter { it in req.files }.toSet()
        if (ids.isEmpty()) return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")

        val created = Session(
            id = UUID.randomUUID().toString(),
            sender = req.info,
            ip = s.remoteIpAddress,
            files = req.files,
            tokens = ids.associateWith { UUID.randomUUID().toString() },
        )
        session = created
        val body = Protocol.json.encodeToString(PrepareUploadResponse.serializer(), PrepareUploadResponse(created.id, created.tokens))
        return json(Response.Status.OK, body)
    }

    private fun upload(s: IHTTPSession): Response {
        val q = s.parameters
        val current = session ?: return text(Response.Status.BAD_REQUEST, "No session")
        val sessionId = q["sessionId"]?.firstOrNull()
        val fileId = q["fileId"]?.firstOrNull()
        val token = q["token"]?.firstOrNull()
        if (sessionId == null || fileId == null || token == null) return text(Response.Status.BAD_REQUEST, "Missing parameters")
        if (sessionId != current.id || current.tokens[fileId] != token || s.remoteIpAddress != current.ip) {
            return text(Response.Status.FORBIDDEN, "Invalid token or IP address")
        }
        val dto = current.files.getValue(fileId)
        val length = s.headers["content-length"]?.toLongOrNull() ?: return text(Response.Status.BAD_REQUEST, "No length")
        if (length > MAX_FILE_BYTES) return text(Response.Status.BAD_REQUEST, "Too large")

        saveDir.mkdirs()
        val name = dto.fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        val target = uniqueFile(name)
        // Do not close the session stream: that would close the socket before we respond.
        DataInputStream(s.inputStream).let { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                if (left > 0) { target.delete(); return text(Response.Status.INTERNAL_ERROR, "Truncated") }
            }
        }
        handler.onFileReceived(current.sender, target.name, target)
        current.pending.remove(fileId)
        if (current.pending.isEmpty()) session = null
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
    }

    private fun cancel(s: IHTTPSession): Response {
        val id = s.parameters["sessionId"]?.firstOrNull()
        val current = session
        if (current != null && current.id == id && current.ip == s.remoteIpAddress) session = null
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
    }

    private fun uniqueFile(name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var f = File(saveDir, name)
        var i = 1
        while (f.exists()) f = File(saveDir, "$base ($i)$ext").also { i++ }
        return f
    }

    private fun readBody(s: IHTTPSession, max: Int): ByteArray {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        require(len in 0..max) { "Body too large" }
        return ByteArray(len).also { DataInputStream(s.inputStream).readFully(it) }
    }

    private fun json(status: Response.IStatus, body: String) = newFixedLengthResponse(status, "application/json", body)
    private fun text(status: Response.IStatus, msg: String) =
        newFixedLengthResponse(status, "application/json", """{"message":${Protocol.json.encodeToString(String.serializer(), msg)}}""")

    companion object {
        /** Watches have little storage: refuse anything bigger than this. */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
    }
}
