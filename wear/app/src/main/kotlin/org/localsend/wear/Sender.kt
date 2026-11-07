package org.localsend.wear

import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface SendResult {
    data object Done : SendResult
    data object Declined : SendResult
    data object Busy : SendResult
    data object PinRequired : SendResult
    data class Failed(val message: String) : SendResult
}

/** Sends a text message to [peer]. Blocking: call from Dispatchers.IO. */
class Sender(private val identity: Identity, private val selfInfo: () -> DeviceInfo) {
    // readTimeout 0: prepare-upload only returns once the remote user accepts or declines.
    private val client = Net.client(identity)

    fun sendText(peer: Peer, text: String): SendResult {
        val bytes = text.toByteArray()
        val fileId = UUID.randomUUID().toString()
        val file = FileDto(fileId, "message.txt", bytes.size.toLong(), "text/plain", preview = text)
        val request = PrepareUploadRequest(selfInfo(), mapOf(fileId to file))
        val scheme = if (peer.https) "https" else "http"
        val base = "$scheme://${peer.ip}:${peer.port}${Protocol.API}"
        val json = "application/json".toMediaType()

        return try {
            val prepare = Request.Builder().url("$base/prepare-upload")
                .post(Protocol.json.encodeToString(PrepareUploadRequest.serializer(), request).toRequestBody(json)).build()
            client.newCall(prepare).execute().use { res ->
                when (res.code) {
                    204 -> return SendResult.Done // text travels in `preview`, nothing to upload
                    200 -> Unit
                    401 -> return SendResult.PinRequired
                    403 -> return SendResult.Declined
                    409 -> return SendResult.Busy
                    else -> return SendResult.Failed("HTTP ${res.code}")
                }
                val accepted = Protocol.json.decodeFromString<PrepareUploadResponse>(res.body!!.string())
                val token = accepted.files[fileId] ?: return SendResult.Done
                val upload = Request.Builder()
                    .url("$base/upload?sessionId=${accepted.sessionId}&fileId=$fileId&token=$token")
                    .post(bytes.toRequestBody("text/plain".toMediaType())).build()
                client.newCall(upload).execute().use {
                    if (it.isSuccessful) SendResult.Done else SendResult.Failed("HTTP ${it.code}")
                }
            }
        } catch (e: Exception) {
            SendResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
