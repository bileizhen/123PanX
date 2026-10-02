package io.github.bileizhen.pan123x.feature.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferDirection
import org.junit.Assert.assertTrue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap
import kotlin.math.roundToInt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.bileizhen.pan123x.MainActivity
import io.github.bileizhen.pan123x.PanXApplication
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 云盘浏览 UI 测试（**无网络**）。直接向真实 Room 预置目录树与目录状态
 * （allLoaded=true，缓存非空，因此 ViewModel 不会自动发起网络刷新），再把内存会话置为
 * 测试账户，即可离线断言列表 / 进目录 / 面包屑 / 返回 / 布局切换 / 搜索 / 详情。
 */
@RunWith(AndroidJUnit4::class)
class FileBrowseTest {
    @Test fun compactCapacityAndPinnedToolbarLeaveFilesInTheFirstViewport() {
        compose.onNodeWithTag("files_space_summary").assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density
        val overview = compose.onNodeWithTag("files_space_summary").fetchSemanticsNode().boundsInRoot
        assertTrue("Capacity summary stays compact", overview.height / density < 96f)
        compose.onNodeWithTag("file_item_100").assertIsDisplayed()
        compose.onNodeWithTag("file_item_101").assertIsDisplayed()
        captureContext("file-browser-root")
        val container = (compose.activity.application as PanXApplication).container
        runBlocking { container.database.cloudFileDao().upsert((300L..330L).map {
            CloudFileEntity(accountId, it, 0, "z-$it.bin", false, size = 4096)
        }) }
        val before = compose.onNodeWithTag("files_directory_toolbar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_330"))
        val after = compose.onNodeWithTag("files_directory_toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("Tools stay pinned while scrolling", kotlin.math.abs(before.top - after.top) < 1f)
        val visibleFile = (300L..330L).first { compose.onNodeWithTag("file_item_$it").isDisplayed() }
        compose.onNodeWithTag("files_layout").performClick(); compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("files_grid").assertIsDisplayed()
        captureContext("file-browser-grid-position")
        compose.onNodeWithTag("file_item_$visibleFile").assertIsDisplayed()
        compose.onNodeWithTag("files_layout").performClick(); compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("file_item_$visibleFile").assertIsDisplayed()
        compose.onNodeWithTag("files_search").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_query").performTextReplacement("jpg")
        compose.runOnIdle { WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_101"))
        captureContext("file-browser-search")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_query").assertDoesNotExist()
        compose.onNodeWithTag("files_add").assertIsDisplayed()
    }

    @Test fun addMenuOffersUploadAndFolderWithAnimatedDismissal() {
        compose.onNodeWithTag("files_add").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_upload").assertIsDisplayed()
        compose.onNodeWithTag("files_create").assertIsDisplayed()
        captureContext("file-browser-add")
        compose.onNodeWithTag("files_create").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_actions_menu").assertDoesNotExist()
        compose.onNodeWithTag("dialog_create_input").assertIsDisplayed()
        compose.onNodeWithTag("dialog_create_ok").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("dialog_create_input").assertDoesNotExist()
    }

    @Test fun selectionReplacesNavigationAndKeepsAllActionsReachable() {
        compose.onNodeWithTag("files_more").performClick(); compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("files_select").performClick(); compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("files_action_download").assertIsNotEnabled()
        compose.onNodeWithTag("tab_0").assertDoesNotExist()
        compose.onNodeWithTag("file_item_104").performClick()
        compose.onNodeWithTag("file_item_101").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("file_context_menu").assertDoesNotExist()
        compose.onNodeWithTag("files_select_count").assertTextContains("已选 2 项")
        captureContext("file-browser-selection")
        compose.onNodeWithTag("files_action_more").performClick(); compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("files_action_copy").assertIsDisplayed()
        compose.onNodeWithTag("files_action_rename").assertIsNotEnabled()
        captureContext("file-browser-batch-more")
        compose.onNodeWithTag("files_action_delete").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_action_delete_confirm").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_select_count").assertTextContains("已选 2 项")
        compose.onNodeWithTag("files_select_close").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_batch_actions").assertDoesNotExist()
        compose.onNodeWithTag("tab_0").assertIsDisplayed()
    }
    @Test fun shareFormProvidesExpiryAndAnimatedPasswordOptions() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_101"))
        compose.onNodeWithTag("file_item_101").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("file_context_share").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("dialog_share_name").assertTextContains("山间日出.jpg")
        compose.onNodeWithText("7 天").performClick()
        compose.onNodeWithText("随机", substring = false).performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("dialog_share_random").assertIsDisplayed()
        captureContext("share-form-random")
        compose.onNodeWithText("自定义").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("dialog_share_ok").performClick()
        compose.onNodeWithTag("dialog_share_error").assertTextContains("请输入 4 位提取码")
        compose.onNodeWithTag("dialog_share_pwd").performTextInput("!bad")
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5_000) { compose.onNodeWithText("分享链接设置").isDisplayed() }
        captureContext("share-form-keyboard")
        compose.onNodeWithTag("dialog_share_ok").performClick()
        compose.onNodeWithTag("dialog_share_error").assertTextContains("提取码需为", substring = true)
        captureContext("share-form-validation")
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("dialog_share_form").assertDoesNotExist()
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val accountId = "m2-browse-test"

    @Before fun seedRoomWithBrowsingFixture() = runBlocking {
        val container = (compose.activity.application as PanXApplication).container
        // 先等启动恢复结束，避免 restoreSession 的 onLogout 覆盖测试会话。
        compose.waitUntil(timeoutMillis = 5_000) {
            container.accountManager.state.value != SessionState.Restoring
        }
        withContext(Dispatchers.IO) {
            // 清掉设备上可能残留的真实账户缓存，保证断言只针对本用例预置数据。
            container.database.clearAllTables()
            container.database.accountDao().upsert(
                AccountEntity(accountId, displayName = "浏览测试账户", uid = "1", usedBytes = 64, totalBytes = 128),
            )
            container.database.cloudFileDao().upsert(
                listOf(
                    CloudFileEntity(accountId, 100, 0, "文档", isFolder = true, updateAt = 1_790_000_000_000L),
                    CloudFileEntity(accountId, 101, 0, "山间日出.jpg", isFolder = false, size = 4_810_240, etag = "etag-101", updateAt = 1_790_640_000_000L),
                    CloudFileEntity(accountId, 102, 0, "旅行回忆.mp4", isFolder = false, size = 872_415_232, updateAt = 1_790_550_000_000L),
                    CloudFileEntity(accountId, 103, 0, "空文件夹", isFolder = true, updateAt = 1_790_000_000_000L),
                    CloudFileEntity(accountId, 201, 100, "会议记录.txt", isFolder = false, size = 4096, updateAt = 1_790_700_000_000L),
                    // M6 起图片/视频/文本/PDF 点击进预览页，详情页入口改用无预览能力的类型。
                    CloudFileEntity(accountId, 104, 0, "档案.zip", isFolder = false, size = 2048, updateAt = 1_790_500_000_000L),
                ),
            )
            // allLoaded=true 且缓存非空：阻止进入页面时的自动网络刷新。
            container.database.directoryStateDao().upsert(DirectoryStateEntity(accountId, 0, total = 4, allLoaded = true, updatedAt = 1L))
            container.database.directoryStateDao().upsert(DirectoryStateEntity(accountId, 100, total = 1, allLoaded = true, updatedAt = 1L))
            container.database.directoryStateDao().upsert(DirectoryStateEntity(accountId, 103, total = 0, allLoaded = true, updatedAt = 1L))
        }
        container.accountManager.onLoginSuccess(accountId, "浏览测试账户", "1", "Bearer browse-test")
        Unit
    }

    @After fun clearSession() {
        // 仅清内存会话（不动凭据存储）：后续用例（如 LoginScreenTest）仍按未登录假设运行。
        (compose.activity.application as PanXApplication).container.accountManager.onLogout()
    }

    @Test fun entersNestedFoldersAndReturnsByBreadcrumbAndBack() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.onNodeWithTag("file_item_100").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_201"))
        compose.onNodeWithTag("file_item_201").assertIsDisplayed()
        compose.onNodeWithText("会议记录.txt").assertIsDisplayed()

        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_101"))
        compose.onNodeWithTag("file_item_101").assertIsDisplayed()

        compose.onNodeWithTag("files_list").performScrollToIndex(0)
        compose.onNodeWithTag("breadcrumb_root").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.onNodeWithTag("file_item_100").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").assertDoesNotExist()
    }

    @Test fun emptyDirectoryShowsDedicatedEmptyState() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_103"))
        compose.onNodeWithTag("file_item_103").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_empty").assertIsDisplayed()
        compose.onNodeWithText("目录为空").assertIsDisplayed()
    }

    @Test fun searchFiltersCurrentDirectoryAndLayoutSwitchKeepsResults() {
        // Room 的 Flow 从查询线程异步投递到 Main：先等列表真正渲染，再开始搜索断言
        // （assertExists 不重试，直接断言会与数据到达产生竞态）。
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("file_item_101").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("files_search").performClick()
        compose.onNodeWithTag("files_query").performTextInput("jpg")
        // IME 动画可以在 scrollToNode 完成后再次压缩窗口，销毁刚找到的懒列表行。
        // 本用例验证搜索/布局切换：输入后先收起 IME，等窗口恢复，再滚动检查结果。
        val decor = compose.activity.window.decorView
        compose.runOnIdle {
            WindowInsetsControllerCompat(compose.activity.window, decor).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_101"))
        compose.onNodeWithTag("file_item_101").assertExists()
        compose.onNodeWithTag("files_list").performScrollToIndex(0)
        compose.onNodeWithTag("file_item_100").assertDoesNotExist()
        compose.onNodeWithText("没有匹配的文件").assertDoesNotExist()

        compose.onNodeWithTag("files_query").performTextReplacement("")
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.onNodeWithTag("file_item_100").assertExists()

        compose.onNodeWithTag("files_layout").performClick()
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("file_item_102").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val diagnostic = buildString {
                append("grid=").append(compose.onAllNodesWithTag("files_grid").fetchSemanticsNodes().size)
                append(" list=").append(compose.onAllNodesWithTag("files_list").fetchSemanticsNodes().size)
                append(" item102=").append(compose.onAllNodesWithTag("file_item_102").fetchSemanticsNodes().size)
                append(" item100=").append(compose.onAllNodesWithTag("file_item_100").fetchSemanticsNodes().size)
            }
            throw AssertionError("切换网格后 5s 未出现 file_item_102 [$diagnostic]", failure)
        }
        compose.onNodeWithTag("file_item_102").assertExists()
    }

    @Test fun opensFileDetailFromCachedEntity() {
        // M6 起图片/视频/文本点击进预览页：详情入口用无预览能力的 .zip（其他 → 详情）。
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_104"))
        // ScrollToNode may leave a row partly behind the floating glass bar; tap an uncovered row.
        val bar = (compose.onAllNodesWithTag("blur_floating_bar").fetchSemanticsNodes() +
            compose.onAllNodesWithTag("solid_floating_bar").fetchSemanticsNodes()).firstOrNull()
        if (bar != null) {
            val fileBounds = compose.onNodeWithTag("file_item_104").fetchSemanticsNode().boundsInRoot
            if (fileBounds.bottom > bar.boundsInRoot.top) {
                compose.onNodeWithTag("files_list").performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, fileBounds.bottom - bar.boundsInRoot.top + 16f)
                }
            }
        }
        compose.onNodeWithTag("file_item_104").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(600)
        // NavDisplay and the Room lookup finish on separate dispatchers, not a fixed virtual delay.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onNodeWithText("ETag").isDisplayed() }
        compose.onNodeWithTag("file_detail").assertIsDisplayed()
        compose.onNodeWithText("档案.zip").assertIsDisplayed()
        compose.onNodeWithText("ETag").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_list").assertIsDisplayed()
    }

    @Test fun backExitsSelectionBeforeLeavingDirectoryWithEitherBackMode() {
        val container = (compose.activity.application as PanXApplication).container
        for (predictive in listOf(false, true)) {
            runBlocking { container.settings.edit { it.copy(predictiveBack = predictive) } }
            compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
            compose.onNodeWithTag("file_item_100").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_201"))
            compose.onNodeWithTag("file_item_201").performTouchInput { longClick() }
            compose.onNodeWithTag("file_context_select").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("files_select_close").assertExists()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("files_select_close").assertDoesNotExist()
            compose.onNodeWithTag("file_item_201").assertExists()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("navigate_back").assertDoesNotExist()
        }
    }

    @Test fun longPressAtTouchPointOpensMenuWithoutBottomActions() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        val row = compose.onNodeWithTag("file_item_100")
        val bounds = row.fetchSemanticsNode().boundsInWindow
        val point = Offset(24f, 24f)
        row.performTouchInput { longClick(point) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("file_context_menu").assertIsDisplayed().assert(SemanticsMatcher.expectValue(
            FileMenuAnchorPoint, IntOffset((bounds.left + point.x).roundToInt(), (bounds.top + point.y).roundToInt()),
        ))
        compose.onNodeWithTag("files_batch_actions").assertDoesNotExist()
        compose.onNodeWithTag("files_select_close").assertDoesNotExist()
        captureContext("list-menu")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("file_context_menu").assertDoesNotExist()
        compose.onNodeWithTag("files_list").assertIsDisplayed()
    }

    @Test fun gridMenuTargetsPressedFileAndRenameStaysOutOfBatchMode() {
        compose.onNodeWithTag("files_layout").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("files_grid").performScrollToNode(hasTestTag("file_item_101"))
        compose.onNodeWithTag("file_item_101").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(500)
        captureContext("grid-menu")
        compose.onNodeWithTag("file_context_rename").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("dialog_rename_input").assertIsDisplayed().assertTextContains("山间日出.jpg")
        compose.onNodeWithTag("files_batch_actions").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("dialog_rename_input").assertDoesNotExist()
        compose.onNodeWithTag("files_grid").assertIsDisplayed()
    }

    @Test fun deleteFromMenuRequiresConfirmationWithoutSendingRequest() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_104"))
        val bar = (compose.onAllNodesWithTag("blur_floating_bar").fetchSemanticsNodes() +
            compose.onAllNodesWithTag("solid_floating_bar").fetchSemanticsNodes()).firstOrNull()
        val row = compose.onNodeWithTag("file_item_104").fetchSemanticsNode().boundsInRoot
        if (bar != null && row.bottom > bar.boundsInRoot.top) {
            compose.onNodeWithTag("files_list").performSemanticsAction(SemanticsActions.ScrollBy) {
                it(0f, row.bottom - bar.boundsInRoot.top + 16f)
            }
        }
        compose.onNodeWithTag("file_item_104").performTouchInput { longClick() }
        compose.onNodeWithTag("file_context_delete").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("files_action_delete_confirm").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("file_item_104").assertExists()
        compose.onNodeWithTag("files_batch_actions").assertDoesNotExist()
    }

    @Test fun contextMenuRetainsExitFramesAndLayoutHasBothTransitionLayers() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.onNodeWithTag("file_item_100").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = false
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("file_context_menu").assertExists()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("file_context_menu").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("files_list").performScrollToIndex(0)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("files_layout").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("files_list").assertExists()
        compose.onNodeWithTag("files_grid").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("files_list").assertDoesNotExist()
        compose.onNodeWithTag("files_grid").assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun contextMoveAndCopyPickerReopensAtRootAfterAnimatedDismiss() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.onNodeWithTag("file_item_100").performTouchInput { longClick() }
        compose.onNodeWithTag("file_context_move").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("移动到").assertIsDisplayed()
        captureContext("picker-open")
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("picker_folder_100").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("picker_confirm").assertDoesNotExist()
        // Closing while still at root must also reset and resubscribe to the cached directory.
        compose.onNodeWithTag("file_item_100").performTouchInput { longClick() }
        compose.onNodeWithTag("file_context_move").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("picker_folder_100").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("picker_folder_100").performClick()
        compose.onNodeWithTag("picker_crumb_100").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("picker_confirm").assertDoesNotExist()
        compose.onNodeWithTag("file_item_100").performTouchInput { longClick() }
        compose.onNodeWithTag("file_context_copy").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("复制到").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("picker_folder_100").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("picker_crumb_100").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("picker_confirm").assertDoesNotExist()
        compose.onNodeWithTag("files_batch_actions").assertDoesNotExist()
    }

    private fun captureContext(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "file-context-menu")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun downloadFromNestedFolderReturnsToActiveTransfersAndClearsOldSearch() {
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("transfer-filter-COMPLETED").performClick()
        compose.onNodeWithTag("transfer_search_toggle").performClick()
        compose.onNodeWithTag("transfer_search").performTextReplacement("previous search")
        compose.runOnIdle {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.onNodeWithTag("tab_0").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.onNodeWithTag("file_item_100").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_201"))
        compose.onNodeWithTag("file_item_201").performTouchInput { longClick() }
        compose.onNodeWithTag("file_context_download").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("tab_1").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("tab_1").assertIsSelected()
        compose.onNodeWithTag("transfer-page").assertIsDisplayed()
        compose.onNodeWithTag("transfer-filter-ACTIVE").assertIsSelected()
        compose.onNodeWithTag("transfer_search").assertDoesNotExist()
        compose.onNodeWithTag("transfer_search_toggle").performClick()
        compose.onNodeWithTag("transfer_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("transfer_search_toggle").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("navigate_back").assertDoesNotExist()
        val container = (compose.activity.application as PanXApplication).container
        val tasks = runBlocking { container.database.transferTaskDao().observeTasks(accountId).first() }
        assertTrue(tasks.any { it.fileId == 201L && it.direction == TransferDirection.DOWNLOAD })
        compose.onNodeWithTag("tab_0").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("file_item_100").assertExists()
        compose.onNodeWithTag("navigate_back").assertDoesNotExist()
    }

    @Test fun directoryFilesAndGridUseScaledEntranceInsteadOfAppearingImmediately() {
        compose.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_item_100"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("file_item_100").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeBy(16)
            compose.onAllNodesWithTag("file_item_201").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(550)
        val partial = compose.onNodeWithTag("file_item_201").fetchSemanticsNode().boundsInWindow.width
        captureContext("files-entrance-half")
        compose.mainClock.advanceTimeBy(600)
        val full = compose.onNodeWithTag("file_item_201").fetchSemanticsNode().boundsInWindow.width
        assertTrue("Directory entrance must grow during its transition: $partial -> $full", partial < full - 4f)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("files_list").performScrollToIndex(0)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("files_layout").performClick()
        compose.mainClock.advanceTimeBy(120)
        val gridPartial = compose.onNode(hasTestTag("file_item_101") and hasAnyAncestor(hasTestTag("files_grid"))).fetchSemanticsNode().boundsInWindow.width
        compose.mainClock.advanceTimeBy(1_000)
        val gridFull = compose.onNodeWithTag("file_item_101").fetchSemanticsNode().boundsInWindow.width
        assertTrue("Grid entrance must grow during its transition", gridPartial < gridFull - 4f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun transferFiltersSlideIndicatorAndAnimateIncomingRows() {
        val container = (compose.activity.application as PanXApplication).container
        runBlocking(Dispatchers.IO) {
            for ((id, status) in listOf("active-fixture" to TransferState.QUEUED, "stopped-fixture" to TransferState.PAUSED, "done-fixture" to TransferState.COMPLETED)) {
                container.database.transferTaskDao().upsert(TransferTaskEntity(accountId, id, fileName = "$id.bin", direction = TransferDirection.DOWNLOAD,
                    state = status, size = 4096, downloadedBytes = 2048, createTime = 1L))
            }
        }
        compose.onNodeWithTag("tab_1").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("transfer-active-fixture").assertIsDisplayed()
        val start = compose.onNodeWithTag("transfer_filter_indicator").fetchSemanticsNode().boundsInWindow.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("transfer-filter-STOPPED").performClick()
        compose.mainClock.advanceTimeBy(96)
        val middle = compose.onNodeWithTag("transfer_filter_indicator").fetchSemanticsNode().boundsInWindow.left
        val rowPartial = compose.onNodeWithTag("transfer-stopped-fixture").fetchSemanticsNode().boundsInWindow.width
        captureContext("transfer-switch-half")
        compose.mainClock.advanceTimeBy(1_000)
        val end = compose.onNodeWithTag("transfer_filter_indicator").fetchSemanticsNode().boundsInWindow.left
        val rowFull = compose.onNodeWithTag("transfer-stopped-fixture").fetchSemanticsNode().boundsInWindow.width
        assertTrue("Filter must slide through an intermediate position", middle > start + 1f && middle < end - 1f)
        assertTrue("Incoming rows must animate", rowPartial < rowFull - 4f)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("transfer-filter-STOPPED").assertIsSelected()
        captureContext("transfer-stopped")
        compose.onNodeWithTag("transfer-filter-ALL").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onNodeWithTag("transfer-filter-ALL").fetchSemanticsNode().config[SemanticsProperties.Selected] }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("transfer-done-fixture").assertIsDisplayed()
        captureContext("transfer-all")
        compose.onNodeWithTag("transfer-done-fixture").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("transfer-detail").assertIsDisplayed()
    }

    @Test fun compactTransferHeaderKeepsSearchAndActionsAccessible() {
        val container = (compose.activity.application as PanXApplication).container
        runBlocking(Dispatchers.IO) {
            for ((id, status) in listOf("active-fixture" to TransferState.QUEUED, "stopped-fixture" to TransferState.PAUSED, "done-fixture" to TransferState.COMPLETED)) {
                container.database.transferTaskDao().upsert(TransferTaskEntity(accountId, id, fileName = "$id.bin", direction = TransferDirection.DOWNLOAD,
                    state = status, size = 4096, downloadedBytes = 2048, createTime = 1L))
            }
        }
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("transfer-filter-ALL").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("transfer_search").assertDoesNotExist()
        compose.onNodeWithTag("transfer_clear_finished").assertDoesNotExist()
        val summary = compose.onNodeWithTag("transfer_summary").fetchSemanticsNode().boundsInWindow
        val firstRow = compose.onNodeWithTag("transfer-done-fixture").fetchSemanticsNode().boundsInWindow
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Collapsed controls must leave room for tasks", firstRow.top - summary.top < 160f * density)
        captureContext("transfer-compact")
        compose.onNodeWithTag("transfer_search_toggle").performClick()
        compose.onNodeWithTag("transfer_search").performTextReplacement("stopped-fixture")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("transfer-stopped-fixture").assertIsDisplayed()
        compose.onNodeWithTag("transfer-done-fixture").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("transfer_search").assertDoesNotExist()
        compose.onNodeWithTag("transfer-page").assertIsDisplayed()
        compose.onNodeWithTag("transfer-done-fixture").assertIsDisplayed()
        compose.onNodeWithTag("transfer_more").performClick()
        compose.onNodeWithTag("transfer_pause_visible").assertExists()
        compose.onNodeWithTag("transfer_resume_visible").assertExists()
        compose.onNodeWithTag("transfer_clear_finished").assertIsDisplayed()
        captureContext("transfer-actions")
        compose.onNodeWithTag("transfer_clear_finished").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.database.transferTaskDao().get(accountId, "done-fixture") } == null
        }
        compose.onNodeWithTag("transfer_actions_menu").assertDoesNotExist()
        compose.onNodeWithTag("transfer-stopped-fixture").assertExists()
        compose.onNodeWithTag("transfer-active-fixture").assertExists()
    }
}
