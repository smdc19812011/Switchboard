package app.switchboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.switchboard.data.Conversation
import kotlinx.coroutines.launch

/** Where pane widths are remembered (SharedPreferences in the app; in memory in screenshot tests). */
class PaneStore(val get: (String, Float) -> Float, val set: (String, Float) -> Unit)

/**
 * Layout rules by window width in dp. Unfolded Fold inner screens are about 670–960 dp wide (Fold 2 upright to
 * Fold 8 Ultra sideways), between phones (cover screens ~360–410 dp) and tablets, so the panes scale with the width.
 */
object PaneRules {
    /** From this width the chat list stays beside the chat (every unfolded Fold); below it, it's a slide-out drawer. */
    const val SIDEBAR_FROM = 600
    /** The chat itself never gets narrower than this when the details panel is a column. */
    const val MIN_CHAT = 440f

    fun defaultSidebar(screen: Float) = (screen * 0.34f).coerceIn(240f, 320f)
    fun maxSidebar(screen: Float) = (screen * 0.45f).coerceAtLeast(260f)
    fun defaultDetails(screen: Float) = (screen * 0.3f).coerceIn(260f, 340f)
    fun maxDetails(screen: Float) = (screen * 0.45f).coerceAtLeast(260f)

    /** Details as a third column only when the chat keeps [MIN_CHAT]; otherwise it slides over the chat. */
    fun detailsAsColumn(screen: Float, sidebar: Float, details: Float) = screen - sidebar - details >= MIN_CHAT

    /** Open the details panel by default only where three columns are comfortable (large tablets, desktop windows). */
    fun detailsOpenAtStart(screen: Float) = screen >= 1200f
}

/** Sidebar, chat and details panel, arranged for the current screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPanes(
    vm: ChatViewModel, store: PaneStore, drawer: DrawerState, onRename: (Conversation) -> Unit, onSettings: () -> Unit,
    onPickModel: () -> Unit, onOpenPage: (String) -> Unit, screenWidthDp: Int = LocalConfiguration.current.screenWidthDp,
    startWithDetails: Boolean = PaneRules.detailsOpenAtStart(screenWidthDp.toFloat()),
) {
    val screen = screenWidthDp.toFloat()
    var paneOpen by rememberSaveable { mutableStateOf(true) }
    var detailsOpen by rememberSaveable { mutableStateOf(startWithDetails) }
    val scope = rememberCoroutineScope()

    if (screenWidthDp >= PaneRules.SIDEBAR_FROM) {
        // Widths are dragged with the dividers and remembered; they're re-fitted when the screen changes (fold, rotate).
        var paneWidth by remember(screenWidthDp) { mutableStateOf(store.get("paneWidth", PaneRules.defaultSidebar(screen)).coerceIn(240f, PaneRules.maxSidebar(screen))) }
        var rightWidth by remember(screenWidthDp) { mutableStateOf(store.get("rightWidth", PaneRules.defaultDetails(screen)).coerceIn(240f, PaneRules.maxDetails(screen))) }
        val density = LocalDensity.current
        val asColumn = PaneRules.detailsAsColumn(screen, if (paneOpen) paneWidth else 0f, rightWidth)
        val overlay = detailsOpen && !asColumn
        if (overlay && androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null)
            androidx.activity.compose.BackHandler { detailsOpen = false }
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(paneOpen) {
                Row {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(paneWidth.dp).fillMaxHeight()) {
                        ChatListPanel(vm, onPick = { vm.open(it) }, onNew = { vm.newChat(it) }, onRename = onRename,
                            onManageProjects = { onOpenPage(Pages.PROJECTS) }, onSettings = onSettings, onOpenPage = onOpenPage,
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding())
                    }
                    PaneDivider(onDrag = { px -> paneWidth = (paneWidth + with(density) { px.toDp().value }).coerceIn(240f, PaneRules.maxSidebar(screen)) },
                        onDragEnd = { store.set("paneWidth", paneWidth) })
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                ChatScreen(vm, onMenu = { paneOpen = !paneOpen }, onSettings = onSettings, onPickModel = onPickModel,
                    showSettings = !paneOpen, onDetails = { detailsOpen = !detailsOpen }, detailsOpen = detailsOpen, onRename = onRename)
                // Not enough room for a third column: the details panel slides over the chat, with a scrim to close it.
                if (overlay) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                        .clickable(remember { MutableInteractionSource() }, null) { detailsOpen = false })
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).width(minOf(rightWidth.dp, maxWidth * 0.9f)).fillMaxHeight()) {
                        DetailsPanel(vm, onClose = { detailsOpen = false }, onOpenPage = onOpenPage, onPickModel = onPickModel,
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding())
                    }
                }
            }
            AnimatedVisibility(detailsOpen && asColumn) {
                Row {
                    PaneDivider(onDrag = { px -> rightWidth = (rightWidth - with(density) { px.toDp().value }).coerceIn(240f, PaneRules.maxDetails(screen)) },
                        onDragEnd = { store.set("rightWidth", rightWidth) })
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(rightWidth.dp).fillMaxHeight()) {
                        DetailsPanel(vm, onClose = null, onOpenPage = onOpenPage, onPickModel = onPickModel,
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding())
                    }
                }
            }
        }
    } else {
        // Phones and Fold cover screens: chat list in a drawer, details in a bottom sheet.
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet {
                ChatListPanel(vm,
                    onPick = { vm.open(it); scope.launch { drawer.close() } },
                    onNew = { projectId -> vm.newChat(projectId); scope.launch { drawer.close() } },
                    onRename = onRename,
                    onManageProjects = { onOpenPage(Pages.PROJECTS); scope.launch { drawer.close() } },
                    onSettings = { onSettings(); scope.launch { drawer.close() } },
                    onOpenPage = { p -> onOpenPage(p); scope.launch { drawer.close() } })
            }
        }) {
            ChatScreen(vm, onMenu = { scope.launch { drawer.open() } }, onSettings = onSettings, onPickModel = onPickModel,
                onDetails = { detailsOpen = !detailsOpen }, detailsOpen = detailsOpen, onRename = onRename)
        }
        if (detailsOpen) ModalBottomSheet(onDismissRequest = { detailsOpen = false }) {
            DetailsPanel(vm, onClose = { detailsOpen = false }, onOpenPage = { p -> detailsOpen = false; onOpenPage(p) },
                onPickModel = { detailsOpen = false; onPickModel() }, modifier = Modifier.navigationBarsPadding())
        }
    }
}

/** The draggable line between panes: a wide invisible touch area with a small visible grip. */
@Composable
fun PaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    Box(Modifier.width(16.dp).fillMaxHeight()
        .draggable(rememberDraggableState(onDrag), Orientation.Horizontal, onDragStopped = { onDragEnd() })
        .semantics { contentDescription = "Resize panel" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
        Box(Modifier.width(6.dp).height(48.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)))
    }
}
