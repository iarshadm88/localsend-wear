package org.localsend.wear

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** LocalSend protocol v2.x constants and DTOs (see packages/core/src/http/dto_v2.rs). */
@OptIn(ExperimentalSerializationApi::class)
object Protocol {
    const val VERSION = "2.2"
    const val PORT = 53317
    const val MULTICAST_GROUP = "224.0.0.167"
    const val API = "/api/localsend/v2"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // version/port/protocol are required on the wire
        explicitNulls = false
    }
}

/** Used for the multicast announcement and for register requests/responses. */
@Serializable
data class DeviceInfo(
    val alias: String,
    val version: String = Protocol.VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String = "",
    val port: Int = Protocol.PORT,
    val protocol: String = "http",
    val download: Boolean = false,
    /** Only present on multicast messages. */
    val announce: Boolean? = null,
)

@Serializable
data class FileDto(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
)

@Serializable
data class PrepareUploadRequest(val info: DeviceInfo, val files: Map<String, FileDto>)

@Serializable
data class PrepareUploadResponse(val sessionId: String, val files: Map<String, String>)

/** A discovered peer. [ip] is always a dialable IPv4 string. */
data class Peer(
    val ip: String,
    val alias: String,
    val deviceType: String?,
    val deviceModel: String?,
    val fingerprint: String,
    val port: Int,
    val https: Boolean,
)

fun DeviceInfo.toPeer(ip: String) = Peer(ip, alias, deviceType, deviceModel, fingerprint, port, protocol == "https")
