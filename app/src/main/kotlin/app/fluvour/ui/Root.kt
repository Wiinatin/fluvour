package app.fluvour.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fluvour.ui.screens.*
import app.fluvour.ui.theme.Fluvour
import app.fluvour.ui.art.GrainOverlay
import app.fluvour.ui.theme.FluvourTheme
import app.fluvour.ui.theme.Themes

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Order and labels come from the finalized UI: Home, Review, Read, Output, Watch.
// Route ids are kept from earlier phases where they still mean the same thing ("cards", "read", "speak").
private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("cards", "Review", Icons.Outlined.Style),
    Tab("read", "Read", Icons.Outlined.MenuBook),
    Tab("speak", "Output", Icons.Outlined.Mic),
    Tab("watch", "Watch", Icons.Outlined.PlayCircleOutline)
)

private fun NavHostController.goTab(route: String) = navigate(route) { popUpTo("home"); launchSingleTop = true }

/** Screens where the learner speaks into the microphone: the video pauses on entry and resumes after. */
private fun isMicRoute(route: String?) = route != null && (route.startsWith("call/") || route.startsWith("topic/"))

@Composable
fun FluvourRoot(vm: AppViewModel = hiltViewModel()) {
    // Owned here, not by the Watch screen, so the video and its place survive switching tabs.
    val playerVm: PlayerViewModel = hiltViewModel()
    // Android 13+: the media notification needs this permission. Asked once, the first time a video is opened (no dialog if already decided).
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val hasVideo = playerVm.ui.collectAsStateWithLifecycle().value.item != null
    LaunchedEffect(hasVideo) { if (hasVideo && Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    FluvourTheme(Themes.byId(s.theme), s.mode, s.targetLang) {
        val nav = rememberNavController()
        val route = nav.currentBackStackEntryAsState().value?.destination?.route
        // Video follows the screen: leaving Watch while playing shows the floating window; mic screens pause it and resume after.
        var prevRoute by remember { mutableStateOf<String?>(null) }
        // The mini window also floats over other apps while this app is in the background (needs "Display over other apps").
        val ctx = LocalContext.current
        val overlay = remember { MiniOverlay(ctx.applicationContext, playerVm) }
        SideEffect { overlay.content = { FluvourTheme(Themes.byId(s.theme), s.mode, s.targetLang) { MiniOverlayWindow(playerVm, overlay) } } }
        val fg = remember { MutableStateFlow(true) }
        val eligible = remember { MutableStateFlow(false) }   // not on Watch (full player) and not on a mic screen (video paused)
        val act = ctx as? ComponentActivity
        DisposableEffect(act) {
            val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) fg.value = true else if (e == Lifecycle.Event.ON_STOP) fg.value = false }
            act?.lifecycle?.addObserver(ob)
            onDispose { act?.lifecycle?.removeObserver(ob); overlay.hide() }
        }
        LaunchedEffect(Unit) {
            combine(playerVm.mini, fg, eligible) { m, f, e -> m == MiniMode.MINI && !f && e }.distinctUntilChanged().collect { on ->
                if (on && android.provider.Settings.canDrawOverlays(ctx)) overlay.show() else overlay.hide()
            }
        }
        LaunchedEffect(Unit) { playerVm.openWatch.collect { nav.goTab("watch") } }
        // First time the window appears without the permission: explain once and open the setting.
        val miniNow = playerVm.mini.collectAsStateWithLifecycle().value
        LaunchedEffect(miniNow) {
            val prefs = ctx.getSharedPreferences("fluvour_mini", 0)
            if (miniNow == MiniMode.MINI && !android.provider.Settings.canDrawOverlays(ctx) && !prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                android.widget.Toast.makeText(ctx, "Allow \"Display over other apps\" to keep the mini player when you leave the app", android.widget.Toast.LENGTH_LONG).show()
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
            }
        }
        LaunchedEffect(route) {
            eligible.value = route != null && route != "watch" && !isMicRoute(route)
            val prev = prevRoute; prevRoute = route
            if (route == null) return@LaunchedEffect
            if (route == "watch") playerVm.onEnterWatch() else if (prev == "watch") playerVm.onLeaveWatch()
            val mic = isMicRoute(route); val wasMic = isMicRoute(prev)
            if (mic && !wasMic) playerVm.micEntered() else if (!mic && wasMic) playerVm.micLeft()
        }
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Fluvour.colors.paper,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),   // screens handle their own system-bar padding
            bottomBar = { if (tabs.any { it.route == route }) BottomBar(route) { nav.goTab(it) } }
        ) { pad ->
            Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                composable("home") {
                    HomeScreen(vm, onGoTab = { nav.goTab(it) }, onSettings = { nav.navigate("settings") }, onProgress = { nav.navigate("progress") })
                }
                composable("cards") {
                    ReviewTab(onBrowse = { nav.navigate("browse") }, onNewCard = { nav.navigate("card") },
                        onEdit = { id -> nav.navigate("card?id=$id") }, onQuiz = { set -> nav.navigate("quiz/$set") })
                }
                composable("browse") {
                    BrowseScreen(vm, onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate(if (id == null) "card" else "card?id=$id") })
                }
                composable("card?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
                    CardEditorScreen(vm, entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
                }
                composable("quiz/{set}", arguments = listOf(navArgument("set") { type = NavType.StringType })) { entry ->
                    QuizScreen(entry.arguments?.getString("set") ?: "mix", s.targetLang, onClose = { nav.popBackStack() })
                }
                composable("read") { ReadScreen(onOpen = { id -> nav.navigate("reader/$id") }, onOpenBook = { sid -> nav.navigate("book/$sid") }) }
                composable("book/{sourceId}", arguments = listOf(navArgument("sourceId") { type = NavType.StringType })) {
                    BookScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("reader/$id") })
                }
                composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ReaderScreen(onBack = { nav.popBackStack() },
                        onOpenChapter = { id -> nav.navigate("reader/$id") { popUpTo("reader/{id}") { inclusive = true } } },   // next/previous replaces this chapter, so Back returns to the book
                        onAddCard = { word, sentence, meaning, sentenceId ->
                        vm.startDraft(word, sentence, meaning, sentenceId); nav.navigate("card")
                    })
                }
                composable("speak") {
                    SpeakScreen(s.targetLang, onCall = { nav.navigate("call/$it") }, onTopic = { nav.navigate("topic/$it") },
                        onJournal = { nav.navigate("journal") }, onHandwriting = { nav.navigate("handwriting/$it") })
                }
                composable("call/{i}", arguments = listOf(navArgument("i") { type = NavType.IntType })) { e ->
                    CallScreen(e.arguments?.getInt("i") ?: 0, s.targetLang, onClose = { nav.popBackStack() })
                }
                composable("topic/{i}", arguments = listOf(navArgument("i") { type = NavType.IntType })) { e ->
                    TopicScreen(e.arguments?.getInt("i") ?: 0, s.targetLang, onClose = { nav.popBackStack() })
                }
                composable("journal") { JournalScreen(s.targetLang, onClose = { nav.popBackStack() }) }
                composable("handwriting/{mode}", arguments = listOf(navArgument("mode") { type = NavType.StringType })) { e ->
                    HandwritingScreen(e.arguments?.getString("mode") ?: "hw", s.targetLang, onClose = { nav.popBackStack() })
                }
                composable("watch") {
                    WatchScreen(s.targetLang, onAddCard = { word, sentence, meaning, sentenceId ->
                        vm.startDraft(word, sentence, meaning, sentenceId); nav.navigate("card")
                    }, vm = playerVm)
                }
                composable("progress") {
                    ProgressScreen(s.targetLang, onBack = { nav.popBackStack() }, onWeekly = { nav.navigate("weekly") })
                }
                composable("weekly") {
                    WeeklyReviewScreen(s.targetLang, onClose = { nav.popBackStack() }, onStartTopic = { nav.popBackStack("home", false); nav.goTab("speak") })
                }
                composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }, onReplayIntro = { nav.navigate("onboarding") }) }
                composable("onboarding") {
                    OnboardingScreen(s.targetLang, s.dailyGoal, onLanguage = { vm.setTarget(it) }, onGoal = { vm.setDailyGoal(it) }, onDone = { nav.popBackStack() })
                }
            }
            // Floating video window: clear of the status bar, the bottom bar (or the system bar when there is none) and the keyboard.
            val barVisible = tabs.any { it.route == route }
            Box(Modifier.fillMaxSize().padding(pad).statusBarsPadding().then(if (barVisible) Modifier else Modifier.navigationBarsPadding()).imePadding()) {
                MiniPlayer(playerVm, visible = route != "watch" && !isMicRoute(route), onReturn = { nav.goTab("watch") })
            }
            }
        }
        // paper grain over the whole app, Fluvour theme only (the other themes are flat on purpose)
        if (Fluvour.style.languageAccent) GrainOverlay()
        }
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxWidth().background(c.nav)) {
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                val on = t.route == current
                val tint = if (on) c.accent else c.navContent
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t.route) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(t.icon, contentDescription = null, Modifier.size(21.dp), tint = tint)
                    Text(t.label, Modifier.padding(top = 3.dp), fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = tint)
                }
            }
        }
    }
}
