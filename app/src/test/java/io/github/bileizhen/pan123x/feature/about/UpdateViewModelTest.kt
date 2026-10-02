@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.bileizhen.pan123x.feature.about

import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.data.settings.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val release = AppRelease("1.0.0", "Release notes", "https://github.com/bileizhen/123PanX/releases/tag/v1.0.0", "https://github.com/bileizhen/123PanX/releases/download/v1.0.0/123PanX-1.0.0.apk", 100, "a".repeat(64))
    private var checks = 0
    private var result: UpdateResult = UpdateResult.Available(release)
    private var downloadedSource: UpdateSource? = null
    private var installs = 0
    private var installResult = UpdateInstallResult.STARTED
    private var permission = true
    private val installer = object : UpdateInstall {
        override fun canInstall() = permission
        override suspend fun request(file: File, release: AppRelease): UpdateInstallResult { installs++; return installResult }
    }
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(checker: UpdateChecker = UpdateChecker { checks++; result }, downloader: UpdateDownload = UpdateDownload { _, source, progress ->
        downloadedSource = source; progress(50, 100); File("verified.apk")
    }): UpdateViewModel = UpdateViewModel(checker, downloader, installer).also { store.put("update", it) }

    @Test fun automaticCurrentIsQuietAndRunsOnlyOnce() = runTest(dispatcher) {
        result = UpdateResult.Current
        val vm = model(); vm.checkAtStartup(true); runCurrent(); vm.checkAtStartup(true); runCurrent()
        assertEquals(1, checks); assertFalse(vm.state.value.visible)
        vm.check(); runCurrent(); assertTrue(vm.state.value.visible); assertEquals(2, checks)
    }
    @Test fun disabledStartupNeverRequestsButManualStillWorks() = runTest(dispatcher) {
        val vm = model(); vm.checkAtStartup(false); vm.checkAtStartup(true); runCurrent()
        assertEquals(0, checks); vm.check(); runCurrent(); assertEquals(1, checks)
    }
    @Test fun automaticFailuresAreQuiet() = runTest(dispatcher) {
        result = UpdateResult.Failed("offline")
        val vm = model(); vm.checkAtStartup(true); runCurrent(); assertFalse(vm.state.value.visible)
        vm.check(); runCurrent(); assertTrue(vm.state.value.visible)
    }
    @Test fun manualDuringStartupCoalescesRequestAndShowsResult() = runTest(dispatcher) {
        val wait = CompletableDeferred<UpdateResult>()
        val vm = model(UpdateChecker { checks++; wait.await() })
        vm.checkAtStartup(true); runCurrent(); vm.check(); runCurrent(); assertEquals(1, checks)
        wait.complete(UpdateResult.Current); runCurrent(); assertTrue(vm.state.value.visible); assertFalse(vm.state.value.checking)
    }
    @Test fun updateFoundAtStartupOpensDialog() = runTest(dispatcher) {
        val vm = model(); vm.checkAtStartup(true); runCurrent(); assertTrue(vm.state.value.visible)
    }
    @Test fun selectedMirrorDownloadsAndRequestsInstallationAutomatically() = runTest(dispatcher) {
        val vm = model(); vm.check(); runCurrent(); vm.selectSource(UpdateSource.MIRROR); vm.downloadUpdate(); runCurrent()
        assertEquals(UpdateSource.MIRROR, downloadedSource); assertEquals(1, installs)
        assertTrue(vm.state.value.download is UpdateDownloadState.Ready); assertFalse(vm.state.value.visible)
    }
    @Test fun permissionReturnContinuesInstallationOnlyWhenGranted() = runTest(dispatcher) {
        installResult = UpdateInstallResult.PERMISSION_REQUIRED; permission = false
        val vm = model(); vm.check(); runCurrent(); vm.downloadUpdate(); runCurrent()
        assertTrue(vm.state.value.permissionRequired); assertTrue(vm.state.value.visible)
        vm.onResume(); runCurrent(); assertEquals(1, installs)
        permission = true; installResult = UpdateInstallResult.STARTED; vm.onResume(); vm.onResume(); runCurrent()
        assertEquals(2, installs); assertFalse(vm.state.value.permissionRequired); assertFalse(vm.state.value.visible)
    }
    @Test fun downloadingLocksSourceAndDismissButCanCancel() = runTest(dispatcher) {
        val vm = model(downloader = UpdateDownload { _, _, progress -> progress(30, 100); awaitCancellation() })
        vm.check(); runCurrent(); vm.downloadUpdate(); runCurrent(); vm.selectSource(UpdateSource.MIRROR); vm.dismiss()
        assertEquals(UpdateSource.GITHUB, vm.state.value.source); assertTrue(vm.state.value.visible)
        assertEquals(UpdateDownloadState.Downloading(30, 100), vm.state.value.download)
        vm.cancelDownload(); runCurrent(); assertEquals(UpdateDownloadState.Idle, vm.state.value.download); assertEquals(0, installs)
    }
    @Test fun failureCanSwitchSourceAndRetryWithoutInstallingBadFile() = runTest(dispatcher) {
        var attempt = 0
        val vm = model(downloader = UpdateDownload { _, source, _ -> if (attempt++ == 0) error("bad digest") else { downloadedSource = source; File("verified.apk") } })
        vm.check(); runCurrent(); vm.downloadUpdate(); runCurrent()
        assertTrue(vm.state.value.download is UpdateDownloadState.Failed); assertEquals(0, installs)
        vm.selectSource(UpdateSource.MIRROR); vm.downloadUpdate(); runCurrent(); assertEquals(UpdateSource.MIRROR, downloadedSource); assertEquals(1, installs)
    }
    @Test fun repeatedClickNeverStartsDuplicateDownloadOrInstall() = runTest(dispatcher) {
        var downloads = 0
        val finish = CompletableDeferred<File>()
        val vm = model(downloader = UpdateDownload { _, _, _ -> downloads++; finish.await() })
        vm.check(); runCurrent(); vm.downloadUpdate(); vm.downloadUpdate(); runCurrent(); assertEquals(1, downloads)
        finish.complete(File("verified.apk")); runCurrent(); assertEquals(1, installs)
        vm.downloadUpdate(); runCurrent(); assertEquals(1, downloads)
    }
}
