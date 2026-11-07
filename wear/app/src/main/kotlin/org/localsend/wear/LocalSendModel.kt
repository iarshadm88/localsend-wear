package org.localsend.wear

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IncomingRequest(val sender: DeviceInfo, val files: List<FileDto>, val answer: CompletableFuture<Set<String>?>)
data class InboxItem(val from: String, val title: String, val isMessage: Boolean)

/** Holds the receiver, discovery and sender for as long as the app is open. */
class LocalSendModel(app: Application) : AndroidViewModel(app), ReceiveHandler {

    private val prefs = app.getSharedPreferences("localsend", 0)
    private val identity = Identity.loadOrCreate()
    private var port = Protocol.PORT

    val alias: String = prefs.getString("alias", null) ?: randomAlias().also { prefs.edit().putString("alias", it).apply() }

    private fun selfInfo() = DeviceInfo(
        alias = alias,
        deviceModel = Build.MODEL,
        deviceType = "mobile",
        fingerprint = identity.fingerprint,
        port = port,
        protocol = "http",
    )

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val _incoming = MutableStateFlow<IncomingRequest?>(null)
    val incoming: StateFlow<IncomingRequest?> = _incoming

    private val _inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    val inbox: StateFlow<List<InboxItem>> = _inbox

    private val _status = MutableStateFlow("Starting…")
    val status: StateFlow<String> = _status

    private val sender = Sender(identity, ::selfInfo)
    private val discovery = Discovery(app, viewModelScope, identity, ::selfInfo, ::addPeer)
    private var server: ReceiveServer? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { startServer(app) }
        discovery.start()
    }

    private fun startServer(app: Application) {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "received")
        for (candidate in Protocol.PORT..Protocol.PORT + 5) {
            port = candidate
            val s = ReceiveServer(candidate, dir, ::selfInfo, this)
            try {
                s.start(30_000)
                server = s
                _status.value = "Visible as $alias"
                return
            } catch (_: Exception) { /* port busy, try next */ }
        }
        _status.value = "Receiving unavailable (port in use)"
    }

    fun refresh() {
        discovery.announce()
        discovery.scan()
    }

    fun sendText(peer: Peer, text: String, onResult: (SendResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { onResult(sender.sendText(peer, text)) }
    }

    fun answer(request: IncomingRequest, accepted: Set<String>?) {
        request.answer.complete(accepted)
        _incoming.value = null
    }

    private fun addPeer(p: Peer) = _peers.update { list -> (list.filterNot { it.fingerprint == p.fingerprint } + p).sortedBy { it.alias } }

    // --- ReceiveHandler (called on server threads) ---

    override fun onRegister(peer: Peer) = addPeer(peer)

    override fun decide(sender: DeviceInfo, files: List<FileDto>): Set<String>? {
        // Watch storage is small: only offer files up to the size limit.
        val ok = files.filter { it.size <= ReceiveServer.MAX_FILE_BYTES }.map { it.id }.toSet()
        if (ok.isEmpty()) return null
        val request = IncomingRequest(sender, files, CompletableFuture())
        _incoming.value = request
        return try {
            request.answer.get(60, TimeUnit.SECONDS)?.intersect(ok)
        } catch (_: TimeoutException) {
            _incoming.compareAndSet(request, null)
            null
        }
    }

    override fun onMessage(sender: DeviceInfo, text: String) =
        _inbox.update { listOf(InboxItem(sender.alias, text, true)) + it }

    override fun onFileReceived(sender: DeviceInfo, name: String, file: File) =
        _inbox.update { listOf(InboxItem(sender.alias, name, false)) + it }

    override fun onCleared() {
        discovery.stop()
        server?.stop()
    }

    private companion object {
        val adjectives = listOf("Cool", "Quick", "Calm", "Bright", "Happy", "Lucky", "Tiny", "Brave")
        val nouns = listOf("Banana", "Orange", "Falcon", "Maple", "Comet", "Otter", "Pixel", "Walnut")
        fun randomAlias() = "${adjectives.random()} ${nouns.random()}"
    }
}
