package org.localsend.wear

import android.app.RemoteInput
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper

private sealed interface Screen {
    data object Home : Screen
    data object Peers : Screen
    data class Compose(val peer: Peer) : Screen
    data object Inbox : Screen
}

private val presets = listOf("On my way", "Running late", "OK", "Call me", "Thanks!")

class MainActivity : ComponentActivity() {
    private val model by viewModels<LocalSendModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(model) } }
    }
}

@Composable
private fun App(model: LocalSendModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val incoming by model.incoming.collectAsState()
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        val request = incoming
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            if (request != null) {
                incomingItems(model, request)
            } else when (val s = screen) {
                Screen.Home -> homeItems(model, onPeers = { model.refresh(); screen = Screen.Peers }, onInbox = { screen = Screen.Inbox })
                Screen.Peers -> peerItems(model, onPick = { screen = Screen.Compose(it) }, onBack = { screen = Screen.Home })
                is Screen.Compose -> composeItems(model, s.peer, onBack = { screen = Screen.Peers })
                Screen.Inbox -> inboxItems(model, onBack = { screen = Screen.Home })
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.homeItems(
    model: LocalSendModel,
    onPeers: () -> Unit,
    onInbox: () -> Unit,
) {
    item { ListHeader { Text("LocalSend") } }
    item { StatusChip(model) }
    item { Chip(onClick = onPeers, label = { Text("Send message") }, modifier = Modifier.fillMaxWidth()) }
    item { Chip(onClick = onInbox, label = { Text("Received") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun StatusChip(model: LocalSendModel) {
    val status by model.status.collectAsState()
    Chip(onClick = {}, label = { Text(status) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.incomingItems(model: LocalSendModel, request: IncomingRequest) {
    item { ListHeader { Text(request.sender.alias) } }
    item {
        val what = request.files.singleOrNull()?.let { it.preview ?: it.fileName } ?: "${request.files.size} files"
        Text(what, maxLines = 4)
    }
    item { Chip(onClick = { model.answer(request, request.files.map { it.id }.toSet()) }, label = { Text("Accept") }, modifier = Modifier.fillMaxWidth()) }
    item { Chip(onClick = { model.answer(request, null) }, label = { Text("Decline") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.peerItems(model: LocalSendModel, onPick: (Peer) -> Unit, onBack: () -> Unit) {
    item { ListHeader { Text("Nearby") } }
    item { PeerList(model, onPick) }
    item { Chip(onClick = onBack, label = { Text("Back") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun PeerList(model: LocalSendModel, onPick: (Peer) -> Unit) {
    val peers by model.peers.collectAsState()
    if (peers.isEmpty()) {
        Text("Searching…")
    } else {
        // One item per peer would need the scope; a column inside one item keeps this simple.
        androidx.compose.foundation.layout.Column {
            peers.forEach { p ->
                Chip(
                    onClick = { onPick(p) },
                    label = { Text(p.alias, maxLines = 1) },
                    secondaryLabel = { Text(p.deviceModel ?: p.deviceType ?: "", maxLines = 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.composeItems(model: LocalSendModel, peer: Peer, onBack: () -> Unit) {
    item { ListHeader { Text("To ${peer.alias}") } }
    item { ComposeBody(model, peer) }
    item { Chip(onClick = onBack, label = { Text("Back") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun ComposeBody(model: LocalSendModel, peer: Peer) {
    var result by remember { mutableStateOf("") }
    fun send(text: String) {
        result = "Sending…"
        model.sendText(peer, text) {
            result = when (it) {
                SendResult.Done -> "Sent"
                SendResult.Declined -> "Declined"
                SendResult.Busy -> "Receiver is busy"
                SendResult.PinRequired -> "PIN required (unsupported)"
                is SendResult.Failed -> "Failed: ${it.message}"
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@rememberLauncherForActivityResult
        RemoteInput.getResultsFromIntent(data)?.getCharSequence("msg")?.toString()?.takeIf { it.isNotBlank() }?.let(::send)
    }

    androidx.compose.foundation.layout.Column {
        Chip(
            onClick = {
                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("msg").setLabel("Message").build()))
                launcher.launch(intent)
            },
            label = { Text("Type or speak…") },
            modifier = Modifier.fillMaxWidth(),
        )
        presets.forEach { p ->
            Chip(onClick = { send(p) }, label = { Text(p) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        if (result.isNotEmpty()) Text(result)
    }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.inboxItems(model: LocalSendModel, onBack: () -> Unit) {
    item { ListHeader { Text("Received") } }
    item { InboxList(model) }
    item { Chip(onClick = onBack, label = { Text("Back") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun InboxList(model: LocalSendModel) {
    val items by model.inbox.collectAsState()
    if (items.isEmpty()) {
        Text("Nothing yet")
    } else {
        androidx.compose.foundation.layout.Column {
            items.forEach {
                Chip(
                    onClick = {},
                    label = { Text(it.title, maxLines = 3) },
                    secondaryLabel = { Text(if (it.isMessage) it.from else "File from ${it.from}", maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
