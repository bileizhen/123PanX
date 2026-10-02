package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.bileizhen.pan123x.core.network.*
import io.github.bileizhen.pan123x.core.share.ShareLinkParser
import io.github.bileizhen.pan123x.data.settings.*
import io.github.bileizhen.pan123x.data.share.*
import io.github.bileizhen.pan123x.feature.share.*
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import java.io.File
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Scaffold

/** Opt-in public-link read check; no user credentials or cloud write methods. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class PublicShareSmokeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun suppliedPublicShareLoadsRealMetadataFileDatesAndOnlineAvatar() {
        val encoded = InstrumentationRegistry.getArguments().getString("shareSmokeUrlBase64")
        assumeTrue("Supply shareSmokeUrlBase64 explicitly to permit public read-only network requests", !encoded.isNullOrBlank())
        val raw = String(java.util.Base64.getDecoder().decode(encoded!!), Charsets.UTF_8)
        val link = requireNotNull(ShareLinkParser.parse(raw))
        val api = PanApi(PanHttpClientFactory.defaultClient(
            device = { DeviceProfile(loginUuid = "0123456789abcdef0123456789abcdef") }, auth = AuthorizationProvider { "" }), fallbackBaseUrl = null)
        val actions = object : SharedFilesActions {
            override suspend fun info(key: String) = api.sharedInfo(key)
            override suspend fun list(key: String, password: String, parentId: Long, page: Int, next: String) = api.sharedFiles(key, password, parentId, page, next)
            override suspend fun save(key: String, password: String, files: List<FileItemDto>, targetId: Long): String? = error("Public smoke check never writes to a drive")
            override suspend fun download(key: String, password: String, files: List<FileItemDto>, tree: String?): SharedQueueResult = error("Public smoke check never downloads shared files")
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLoader = SingletonImageLoader.get(context)
        val onlineLoader = ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(OkHttpClient())) }.build()
        val store = ViewModelStore()
        val vm = SharedFilesViewModel(link, actions)
        store.put("public-share", vm)
        SingletonImageLoader.setUnsafe(onlineLoader)
        try {
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Not used")
            }
            compose.setContent { PanXTheme(AppSettings(themeMode = ThemeMode.LIGHT, monet = false)) {
                Scaffold { Box(Modifier.fillMaxSize()) { SharedFilesScreen(vm, factory, {}, loggedIn = false) } }
            } }
            compose.waitUntil(40_000) { !vm.state.value.loading && !vm.state.value.infoLoading }
            compose.runOnIdle {
                assertNull(vm.state.value.error)
                assertNull(vm.state.value.infoError)
                assertTrue(vm.state.value.info!!.owner.isNotBlank())
                assertTrue(vm.state.value.info!!.avatar.startsWith("https://"))
                assertTrue(vm.state.value.files.isNotEmpty())
                assertTrue(vm.state.value.files.all { it.updateAt > 0 })
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("shared-avatar-online").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shared-avatar-online").assertIsDisplayed()
            compose.onNodeWithTag("shared-files-root").assertIsDisplayed()
            compose.onNodeWithTag("shared-files-save").assertIsDisplayed().assertIsNotEnabled()
            val directory = File(context.getExternalFilesDir(null), "share-design").apply { mkdirs() }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(directory, "share-live-online.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        } finally {
            compose.runOnIdle { store.clear() }
            SingletonImageLoader.setUnsafe(originalLoader)
            onlineLoader.shutdown()
        }
    }
}
