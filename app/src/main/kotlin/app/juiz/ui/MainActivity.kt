package app.juiz.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.juiz.ui.theme.J
import app.juiz.ui.theme.JuizTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data object Home : Route
    data object Tasks : Route
    data object Memory : Route
    data object Archive : Route
    data object Settings : Route
    data class TaskDetail(val id: String) : Route
    data class Page(val key: String) : Route
}

/** 数据变更后递增，所有 query 自动重新加载。 */
object UiBus {
    val tick = MutableStateFlow(0)
    fun bump() { tick.value++ }
    val scope: CoroutineScope = MainScope()
}

@Composable
fun <T> query(vararg keys: Any?, block: () -> T): T? {
    val tick by UiBus.tick.collectAsState()
    return produceState<T?>(null, tick, *keys) { value = withContext(Dispatchers.IO) { runCatching(block).getOrNull() } }.value
}

/** 在后台执行一次变更，完成后刷新界面；异常以提示返回。 */
fun act(onError: (String) -> Unit = {}, block: suspend () -> Unit) {
    UiBus.scope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            onError(e.message ?: e.toString())
        }
        UiBus.bump()
    }
}

class MainActivity : FragmentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { UiBus.bump() }
    private val permsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { UiBus.bump() }
    var pickFolder: ((Uri) -> Unit)? = null
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { pickFolder?.invoke(it) } }
    var createDoc: ((Uri) -> Unit)? = null
    private val createLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { createDoc?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { JuizTheme { Root(this) } }
    }

    override fun onResume() {
        super.onResume()
        UiBus.bump()
    }

    fun requestDialerRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    fun requestPermissions() = permsLauncher.launch(
        arrayOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
        ),
    )

    fun requestBatteryExemption() = runCatching {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    fun openFolder(onPicked: (Uri) -> Unit) { pickFolder = onPicked; folderLauncher.launch(null) }

    fun createFile(name: String, onCreated: (Uri) -> Unit) { createDoc = onCreated; createLauncher.launch(name) }

    /** 主人身份确认：系统生物识别或锁屏密码。未设置锁屏的设备直接放行（审批界面会另行提示）。 */
    fun confirmOwner(title: String, onOk: () -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            onOk()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("只有本人能批准外发").setAllowedAuthenticators(auth).build())
    }
}

fun Context.shareText(subject: String, text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Root(activity: MainActivity) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val current = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun tab(r: Route) { stack.clear(); stack.add(r) }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val c = J.c
    val tabs = listOf(
        Triple(Route.Home, "概览", Icons.Outlined.AutoAwesome),
        Triple(Route.Tasks, "委托", Icons.Outlined.Inventory2),
        Triple(Route.Memory, "记忆", Icons.Outlined.Psychology),
        Triple(Route.Archive, "档案", Icons.Outlined.Archive),
    )
    Column(Modifier.fillMaxSize().nightGrid(c)) {
        Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            when (val r = current) {
                Route.Home -> HomeScreen(activity, ::go)
                Route.Tasks -> TasksScreen(::go)
                Route.Memory -> MemoryScreen(activity)
                Route.Archive -> ArchiveScreen(activity)
                Route.Settings -> SettingsScreen(activity, ::go)
                is Route.TaskDetail -> TaskDetailScreen(activity, r.id) { stack.removeAt(stack.lastIndex) }
                is Route.Page -> SettingsPage(activity, r.key, ::go) { stack.removeAt(stack.lastIndex) }
            }
        }
        if (current in tabs.map { it.first } || stack.size == 1) {
            NavigationBar(containerColor = c.bgDeep, tonalElevation = 0.dp, modifier = Modifier.navigationBarsPadding()) {
                tabs.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = { tab(route) },
                        icon = { Icon(icon, label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.sora, selectedTextColor = c.sora, indicatorColor = c.sora.copy(alpha = 0.12f),
                            unselectedIconColor = c.faint, unselectedTextColor = c.faint,
                        ),
                    )
                }
            }
        }
    }
}
