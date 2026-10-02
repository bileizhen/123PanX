package io.github.bileizhen.pan123x.feature.login

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bileizhen.pan123x.MainActivity
import io.github.bileizhen.pan123x.PanXApplication
import io.github.bileizhen.pan123x.core.account.SessionState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 登录页表单校验与导航（M1 门禁）：不访问真实服务器，只走 UI 表单逻辑。
 * 用例假定干净安装（无已恢复会话）；已登录设备上跳过，避免依赖账号状态。
 */
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginEntryOpensFormAndSubmitRequiresBothFields() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            (compose.activity.application as PanXApplication).container.accountManager.state.value != SessionState.Restoring
        }
        val container = (compose.activity.application as PanXApplication).container
        assumeTrue("已登录状态下没有登录入口，跳过表单用例", container.accountManager.state.value == SessionState.LoggedOut)

        compose.onNodeWithTag("account_login_entry").performClick()
        // Miuix NavDisplay 的常规转场为 500ms，显式推进 Compose 时钟。
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("login_passport").assertExists()
        compose.onNodeWithTag("login_password").assertExists()

        // 两项输入均非空才允许提交；不真正点提交，避免发出真实网络请求。
        compose.onNodeWithTag("login_submit").assertIsNotEnabled()
        compose.onNodeWithTag("login_passport").performClick()
        compose.onNodeWithTag("login_passport").performTextInput("demo@example.invalid")
        compose.onNodeWithTag("login_submit").assertIsNotEnabled()
        compose.onNodeWithTag("login_password").performClick()
        compose.onNodeWithTag("login_password").performTextInput("mock-password")
        compose.onNodeWithTag("login_submit").assertIsEnabled()
    }
}
