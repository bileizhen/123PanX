package io.github.bileizhen.pan123x.data.offline

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.OfflineResolvedItem
import io.github.bileizhen.pan123x.core.network.OfflineResource
import io.github.bileizhen.pan123x.core.network.OfflineSubmittedTask
import io.github.bileizhen.pan123x.core.network.PanOfflineApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OfflineRepository 行为测试（，：纯 JVM、不触网）。
 *
 * 手写 PanOfflineApi 替身 + 真 AccountManager / AppLogger，对齐 ShareRepositoryTest 风格：
 * - 相位推进 Idle → Resolving → Resolved → Submitting（替身挂起构造观察窗口）→ Done；
 * - resolve 失败 / 空输入 / 未登录置 Error 且文案用户可读；
 * - submit 汇总 task_list、空选择拦截、失败保留 items；
 * - reset 回 Idle；code==2 经 relogin 重登一次并仅重试一次。
 */
class OfflineRepositoryTest {

    /** 可编排响应序列并记录调用的 API 替身；[gate] 非空时 submit 挂起直至放行。 */
    private class FakePanOfflineApi : PanOfflineApi {
        val resolveCalls = mutableListOf<String>()
        val submitCalls = mutableListOf<List<OfflineResource>>()
        private val resolveQueue = ArrayDeque<ApiResult<List<OfflineResolvedItem>>>()
        private val submitQueue = ArrayDeque<ApiResult<List<OfflineSubmittedTask>>>()
        var gate: CompletableDeferred<Unit>? = null

        fun enqueueResolve(result: ApiResult<List<OfflineResolvedItem>>) {
            resolveQueue.addLast(result)
        }

        fun enqueueSubmit(result: ApiResult<List<OfflineSubmittedTask>>) {
            submitQueue.addLast(result)
        }

        override suspend fun resolve(urls: String): ApiResult<List<OfflineResolvedItem>> {
            resolveCalls.addLast(urls)
            return resolveQueue.removeFirstOrNull() ?: error("缺少第 ${resolveCalls.size} 次 resolve 响应")
        }

        override suspend fun submit(resources: List<OfflineResource>): ApiResult<List<OfflineSubmittedTask>> {
            gate?.await()
            submitCalls.addLast(resources)
            return submitQueue.removeFirstOrNull() ?: error("缺少第 ${submitCalls.size} 次 submit 响应")
        }
    }

    private class ReloginStub {
        var result = true
        var calls = 0
        val invoke: suspend () -> Boolean = {
            calls++
            result
        }
    }

    private class Fixture(loggedIn: Boolean = true) {
        val api = FakePanOfflineApi()
        val manager = AccountManager()
        val relogin = ReloginStub()
        val repository = OfflineRepository(
            api = api,
            manager = manager,
            relogin = relogin.invoke,
            logger = AppLogger(),
        )

        init {
            if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        }
    }

    private companion object {
        fun resolvedItem(
            resourceId: Long,
            ok: Boolean = true,
            errMessage: String = "",
        ) = OfflineResolvedItem(
            url = "http://example.com/$resourceId",
            type = 1,
            ok = ok,
            name = "res-$resourceId",
            size = 100L * resourceId,
            resourceId = resourceId,
            fileNums = 0,
            files = emptyList(),
            errMessage = errMessage,
        )

        fun submittedTask(id: String, ok: Boolean, errMessage: String = "") =
            OfflineSubmittedTask(taskId = id, ok = ok, errMessage = errMessage)
    }

    // ------------------------------------------------------------------
    // resolve
    // ------------------------------------------------------------------

    @Test
    fun resolveProgressesIdleToResolvingToResolved() = runTest {
        val f = Fixture()
        assertEquals(OfflineState.Idle, f.repository.state.value)

        f.api.enqueueResolve(ApiResult.Success(listOf(resolvedItem(1), resolvedItem(2, ok = false, errMessage = "链接不支持"))))
        f.repository.resolve(" http://example.com/1 ")

        val state = f.repository.state.value
        val resolved = state as? OfflineState.Resolved ?: error("期望 Resolved，实际 $state")
        assertEquals(2, resolved.items.size)
        assertEquals(true, resolved.items[0].ok)
        assertEquals(false, resolved.items[1].ok)
        // 输入 trim 后逐字发送
        assertEquals(listOf("http://example.com/1"), f.api.resolveCalls)
    }

    @Test
    fun resolveApiFailureSetsErrorWithReadableMessage() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.ApiError(code = 4000, message = "链接格式错误"))

        f.repository.resolve("http://a")

        assertEquals(OfflineState.Error("链接格式错误"), f.repository.state.value)
    }

    @Test
    fun resolveNetworkFailureFallsBackToFixedMessage() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.NetworkError("down"))

        f.repository.resolve("http://a")

        assertEquals(
            OfflineState.Error("网络连接失败，请检查网络后重试"),
            f.repository.state.value,
        )
    }

    @Test
    fun resolveBlankInputSetsErrorWithoutCallingApi() = runTest {
        val f = Fixture()

        f.repository.resolve("   \n ")

        assertEquals(OfflineState.Error("请输入下载链接"), f.repository.state.value)
        assertTrue(f.api.resolveCalls.isEmpty())
    }

    @Test
    fun resolveWhenLoggedOutSetsErrorWithoutCallingApi() = runTest {
        val f = Fixture(loggedIn = false)

        f.repository.resolve("http://a")

        assertEquals(OfflineState.Error("请先登录"), f.repository.state.value)
        assertTrue(f.api.resolveCalls.isEmpty())
    }

    @Test
    fun resolveSessionExpiredReloginsOnceAndRetriesOnce() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.SessionExpired)
        f.api.enqueueResolve(ApiResult.Success(listOf(resolvedItem(1))))

        f.repository.resolve("http://a")

        val state = f.repository.state.value
        assertTrue(state is OfflineState.Resolved)
        assertEquals(1, f.relogin.calls)
        assertEquals(2, f.api.resolveCalls.size)
    }

    @Test
    fun resolveSessionExpiredWithFailedReloginStaysError() = runTest {
        val f = Fixture()
        f.relogin.result = false
        f.api.enqueueResolve(ApiResult.SessionExpired)

        f.repository.resolve("http://a")

        assertEquals(
            OfflineState.Error("登录状态已失效，请重新登录"),
            f.repository.state.value,
        )
        assertEquals(1, f.api.resolveCalls.size)
        assertEquals(1, f.relogin.calls)
    }

    // ------------------------------------------------------------------
    // submit
    // ------------------------------------------------------------------

    @Test
    fun submitExposesSubmittingPhaseThenDoneSummary() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.Success(listOf(resolvedItem(5), resolvedItem(9))))
        f.repository.resolve("http://a")
        f.api.enqueueSubmit(
            ApiResult.Success(
                listOf(submittedTask("t-1", ok = true), submittedTask("t-2", ok = false, errMessage = "额度不足")),
            ),
        )
        f.api.gate = CompletableDeferred()

        val job = launch { f.repository.submit(mapOf(5L to listOf(1L, 2L))) }
        runCurrent()
        val submitting = f.repository.state.value as? OfflineState.Submitting
            ?: error("期望 Submitting，实际 ${f.repository.state.value}")
        assertEquals(2, submitting.items.size)
        f.api.gate?.complete(Unit)
        job.join()

        val done = f.repository.state.value as? OfflineState.Done
            ?: error("期望 Done，实际 ${f.repository.state.value}")
        assertEquals(2, done.summary.total)
        assertEquals(1, done.summary.succeeded)
        assertEquals(1, done.summary.failed)
        assertEquals("额度不足", done.summary.firstFailure)
        assertEquals(2, done.items.size)
        // 资源映射逐字透传
        assertEquals(listOf(OfflineResource(5L, listOf(1L, 2L))), f.api.submitCalls.single())
    }

    @Test
    fun submitEmptyTaskListSummarizesAsNoServerResult() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.Success(listOf(resolvedItem(1))))
        f.repository.resolve("http://a")
        f.api.enqueueSubmit(ApiResult.Success(emptyList()))

        f.repository.submit(mapOf(1L to emptyList()))

        val done = f.repository.state.value as? OfflineState.Done
            ?: error("期望 Done，实际 ${f.repository.state.value}")
        assertEquals(0, done.summary.total)
        assertEquals(0, done.summary.succeeded)
        assertEquals("服务器未返回任务结果", done.summary.firstFailure)
    }

    @Test
    fun submitEmptySelectionSetsErrorWithoutCallingApi() = runTest {
        val f = Fixture()

        f.repository.submit(emptyMap())

        assertEquals(OfflineState.Error("请先勾选要离线下载的资源"), f.repository.state.value)
        assertTrue(f.api.submitCalls.isEmpty())
    }

    @Test
    fun submitApiFailureKeepsResolvedItemsInError() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.Success(listOf(resolvedItem(7))))
        f.repository.resolve("http://a")
        f.api.enqueueSubmit(ApiResult.NetworkError("down"))

        f.repository.submit(mapOf(7L to emptyList()))

        val error = f.repository.state.value as? OfflineState.Error
            ?: error("期望 Error，实际 ${f.repository.state.value}")
        assertEquals("网络连接失败，请检查网络后重试", error.userMessage)
        assertEquals(1, error.items.size)
    }

    @Test
    fun submitWhenLoggedOutSetsErrorWithoutCallingApi() = runTest {
        val f = Fixture(loggedIn = false)

        f.repository.submit(mapOf(1L to emptyList()))

        assertEquals(OfflineState.Error("请先登录"), f.repository.state.value)
        assertTrue(f.api.submitCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // reset
    // ------------------------------------------------------------------

    @Test
    fun resetReturnsToIdleFromAnyPhase() = runTest {
        val f = Fixture()
        f.api.enqueueResolve(ApiResult.Success(listOf(resolvedItem(1))))
        f.repository.resolve("http://a")
        assertTrue(f.repository.state.value is OfflineState.Resolved)

        f.repository.reset()

        assertEquals(OfflineState.Idle, f.repository.state.value)
    }
}
