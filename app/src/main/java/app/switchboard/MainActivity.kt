@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.switchboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import app.switchboard.ui.ChatListPanel
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.Conversation
import app.switchboard.ui.ChatScreen
import app.switchboard.ui.DetailsPanel
import app.switchboard.ui.AppPanes
import app.switchboard.ui.PaneStore
import app.switchboard.ui.ChatViewModel
import app.switchboard.ui.ManagePage
import app.switchboard.ui.ModelSheet
import app.switchboard.ui.Pages
import app.switchboard.ui.Picker
import app.switchboard.ui.SettingsScreen
import app.switchboard.ui.SwitchboardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            SwitchboardTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var page by rememberSaveable { mutableStateOf<String?>(null) }
                var showModels by rememberSaveable { mutableStateOf(false) }
                var renaming by remember { mutableStateOf<Conversation?>(null) }
                // Why the app closed last time (crash, out of memory, not responding), shown once.
                var exitReport by remember { mutableStateOf(CrashReport.takeLast(this@MainActivity)) }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val ctx = LocalContext.current

                // System permission dialog for device tools (Bluetooth etc.), requested from the ViewModel.
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res -> vm.onPermissionsResult(res.values.all { it }) }
                LaunchedEffect(vm.permissionRequest) { vm.permissionRequest?.let { permLauncher.launch(it) } }
                LaunchedEffect(vm.oauthStatus) { vm.oauthStatus?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }

                BackHandler(enabled = page != null) { page = null }
                BackHandler(enabled = showSettings && page == null) { showSettings = false }
                BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
                BackHandler(enabled = vm.terminalOpen && page == null && !showSettings) { vm.terminalOpen = false }

                if (page != null) { ManagePage(vm, page!!, onBack = { page = null }); return@SwitchboardTheme }
                if (showSettings) { SettingsScreen(vm, onBack = { showSettings = false }, onOpen = { page = it }); return@SwitchboardTheme }

                // Sidebar, chat and details panel, sized for the screen (phones, every Fold's cover and inner screen, tablets).
                val prefs = remember { ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
                AppPanes(vm, store = PaneStore(get = { k, d -> prefs.getFloat(k, d) }, set = { k, v -> prefs.edit().putFloat(k, v).apply() }),
                    drawer = drawer, onRename = { renaming = it }, onSettings = { showSettings = true }, onPickModel = { showModels = true },
                    onOpenPage = { page = it })
                if (showModels) ModelSheet(vm, onDismiss = { showModels = false }, onEditProviders = { showSettings = true })
                renaming?.let { c -> RenameDialog(vm, c) { renaming = null } }
                exitReport?.let { r ->
                    AlertDialog(onDismissRequest = { exitReport = null }, title = { Text(r.title) },
                        text = {
                            Column(Modifier.androidxVerticalScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(r.explanation)
                                Text(r.details.take(1500), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        confirmButton = { TextButton(onClick = { exitReport = null }) { Text("OK") } },
                        dismissButton = { TextButton(onClick = {
                            val cm = getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Switchboard report", r.title + "\n" + r.explanation + "\n\n" + r.details))
                            Toast.makeText(this@MainActivity, "Details copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy details") } })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.reloadConversations()
    }

    /** OAuth redirects (switchboard://oauth) and taps on scheduled-task notifications. */
    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "switchboard" && data.host == "oauth") { vm.onOAuthRedirect(data); intent.data = null }
        intent?.getStringExtra("conversationId")?.let { vm.openById(it); intent.removeExtra("conversationId") }
        // Files shared from other apps become attachments in the open chat.
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uri != null) vm.addAttachments(listOf(uri))
                intent.action = null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
                vm.addAttachments(uris)
                intent.action = null
            }
        }
    }
}

@Composable
private fun Modifier.androidxVerticalScroll() = this.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())

@Composable
private fun RenameDialog(vm: ChatViewModel, c: Conversation, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(c.title) }
    var project by remember { mutableStateOf(c.projectId) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vm.settings.projects.isNotEmpty())
                    Picker("Project", listOf<Pair<String?, String>>(null to "None") + vm.settings.projects.map { it.id to it.name }, project) { project = it }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { vm.renameChat(c.id, title, project); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

