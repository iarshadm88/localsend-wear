package org.localsend.wear

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Multicast discovery (protocol v2.2, UDP is announce-only; answers go back as an HTTP
 * register request) plus a subnet scan fallback for networks where multicast does not work.
 */
class Discovery(
    context: Context,
    private val scope: CoroutineScope,
    private val identity: Identity,
    private val selfInfo: () -> DeviceInfo,
    private val onPeer: (Peer) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val client: OkHttpClient = Net.client(identity, connectTimeoutMs = 1_000, readTimeoutMs = 2_000)
    private var lock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var job: Job? = null
    private val jsonType = "application/json".toMediaType()

    fun start() {
        lock = wifi.createMulticastLock("localsend").apply { setReferenceCounted(false); acquire() }
        job = scope.launch(Dispatchers.IO) {
            try {
                val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
                val s = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(Protocol.PORT))
                    joinGroup(group)
                }
                socket = s
                announce()
                val buf = ByteArray(65_536)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val ip = packet.address.hostAddress ?: continue
                    val info = runCatching {
                        Protocol.json.decodeFromString<DeviceInfo>(String(packet.data, 0, packet.length))
                    }.getOrNull() ?: continue
                    if (info.fingerprint == identity.fingerprint) continue
                    onPeer(info.toPeer(ip))
                    if (info.announce == true) launch { register(info.toPeer(ip)) }
                }
            } catch (_: Exception) {
                // Socket closed in stop(), or multicast is unavailable: the subnet scan still works.
            }
        }
    }

    /** Sends the announcement burst (3x, as the reference implementation does). */
    fun announce() {
        scope.launch(Dispatchers.IO) {
            val payload = Protocol.json.encodeToString(DeviceInfo.serializer(), selfInfo().copy(announce = true)).toByteArray()
            val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
            repeat(3) {
                runCatching { socket?.send(DatagramPacket(payload, payload.size, group, Protocol.PORT)) }
                delay(100L * (it + 1))
            }
        }
    }

    /** Answers an announcement: POST /register to the announcing device. */
    private fun register(peer: Peer) {
        val body = Protocol.json.encodeToString(DeviceInfo.serializer(), selfInfo()).toRequestBody(jsonType)
        val scheme = if (peer.https) "https" else "http"
        runCatching {
            client.newCall(Request.Builder().url("$scheme://${peer.ip}:${peer.port}${Protocol.API}/register").post(body).build())
                .execute().close()
        }
    }

    /** Probes every address of the local /24 with a register request; responders become peers. */
    fun scan() {
        scope.launch(Dispatchers.IO) {
            val gate = Semaphore(32)
            val body = Protocol.json.encodeToString(DeviceInfo.serializer(), selfInfo())
            for (local in Net.localIpv4()) {
                val prefix = local.substringBeforeLast('.')
                for (i in 1..254) {
                    val ip = "$prefix.$i"
                    if (ip == local) continue
                    launch { gate.withPermit { probe(ip, body) } }
                }
            }
        }
    }

    private fun probe(ip: String, body: String) {
        for (scheme in listOf("https", "http")) {
            val res = runCatching {
                client.newCall(
                    Request.Builder().url("$scheme://$ip:${Protocol.PORT}${Protocol.API}/register")
                        .post(body.toRequestBody(jsonType)).build(),
                ).execute()
            }.getOrNull() ?: continue
            res.use {
                if (!it.isSuccessful) return
                val info = runCatching { Protocol.json.decodeFromString<DeviceInfo>(it.body!!.string()) }.getOrNull() ?: return
                if (info.fingerprint != identity.fingerprint) {
                    onPeer(info.copy(port = Protocol.PORT, protocol = scheme).toPeer(ip))
                }
                return
            }
        }
    }

    fun stop() {
        job?.cancel()
        runCatching { socket?.close() }
        runCatching { lock?.release() }
    }
}
