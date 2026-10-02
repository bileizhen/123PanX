// 秒传导入 Tab 状态容器（协议真源 .reference/123pan service/offline_service.py:100-381）。
package io.github.bileizhen.pan123x.feature.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidCodec
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidFile
import io.github.bileizhen.pan123x.data.transfer.RapidProgress
import io.github.bileizhen.pan123x.data.transfer.RapidImportApi
import io.github.bileizhen.pan123x.data.transfer.RapidReport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 秒传导入 Tab 对 [io.github.bileizhen.pan123x.data.transfer.RapidRepository] 的窄接口
 * （B 的仓库是具体类，由其反向实现本接口，VM 面向接口以便纯 JVM 单测注入替身）。
 *
 * 【依赖 B 形态确认 #2 —— RapidProgress】假设形态为
 * `data class RapidProgress(val done: Int, val total: Int, val currentPath: String)`，
 * 且 `progress` 为 null 表示空闲；导入中由仓库持续更新，VM 仅透传展示。
 *
 * 【依赖 B 形态确认 #3 —— RapidReport】假设形态为
 * `data class RapidReport(val success: List<String>, val failed: List<Pair<String, String>>)`，
 * 其中 failed 元素 = (path, 失败原因)，供 UI 展开失败明细。
 *
 * 另一形态假设（合并时一并核对）：协作取消经 [import] 的 cancel 回调进行，取消后仓库
 * 按"已成功部分"正常返回报告（不抛 CancellationException）；若 B 选择以异常表达取消，
 * VM 的 finally 已保证 importing 复位，仅需在合并时补充报告文案。
 */


/** 秒传导入 Tab 的页面状态（空 / 错误 / 进行中 / 完成汇总显式区分）。 */
data class RapidUiState(
    val input: String = "",
    /** 最近一次解析成功的文件清单（导入期间保持展示）。 */
    val files: List<RapidFile> = emptyList(),
    /** 解析结果总大小（展示用汇总）。 */
    val totalSize: Long = 0L,
    /** 解析失败文案（RapidCodec.parse 的 IAE message 或空结果提示）。 */
    val parseError: String? = null,
    /** 导入目标目录；0 = 根目录。 */
    val parentDirId: Long = 0L,
    val importing: Boolean = false,
    /** 导入实时进度（done/total + 当前 path）；仅导入中透传，结束后隐藏。 */
    val progress: RapidProgress? = null,
    /** 用户已请求取消（等待仓库协作退出，期间按钮不可重复触发）。 */
    val cancelRequested: Boolean = false,
    /** 完成汇总；null = 尚无结果。 */
    val report: RapidReport? = null,
)

/**
 * 秒传导入 Tab 的 ViewModel。
 *
 * 职责边界：解析调 [RapidCodec.parse]（纯 JVM，IAE 文案直接透出）；
 * 导入委托 [RapidImportApi] 并透传进度；取消经 [AtomicBoolean] 置协作标志。
 * 纯 JVM 可测（注入替身仓库与替身解析函数）。
 */
class RapidImportViewModel(
    private val rapid: RapidImportApi,
    /** 解析函数默认接冻结的 RapidCodec.parse；测试注入替身（同 ShareViewModel 的 copy 注入先例）。 */
    private val parseText: (String) -> List<RapidFile> = { RapidCodec.parse(it) },
) : ViewModel() {

    /** VM 侧输入态：文本、解析结果、目标目录。 */
    private data class Inputs(
        val input: String = "",
        val files: List<RapidFile> = emptyList(),
        val parseError: String? = null,
        val parentDirId: Long = 0L,
    )

    /** VM 侧运行态：导入中、取消已请求、完成汇总。 */
    private data class Run(
        val importing: Boolean = false,
        val cancelRequested: Boolean = false,
        val report: RapidReport? = null,
    )

    private val inputs = MutableStateFlow(Inputs())
    private val runState = MutableStateFlow(Run())

    /** 协作取消标志；经 [requestCancel] 置位，随每次导入开始清零。 */
    private val cancelFlag = AtomicBoolean(false)

    val uiState: StateFlow<RapidUiState> = combine(inputs, runState, rapid.progress) { inputs, run, progress ->
        RapidUiState(
            input = inputs.input,
            files = inputs.files,
            totalSize = inputs.files.sumOf { it.size },
            parseError = inputs.parseError,
            parentDirId = inputs.parentDirId,
            importing = run.importing,
            progress = if (run.importing) progress else null,
            cancelRequested = run.cancelRequested,
            report = run.report,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RapidUiState())

    // ---- 用户动作 ----

    fun updateInput(text: String) {
        inputs.update { it.copy(input = text, parseError = null) }
    }

    /**
     * 解析预览：调 [parseText]（默认 RapidCodec.parse）；IAE 捕获后把其 message
     * 作为用户可读文案展示（不弹栈）；空结果视为无效内容提示。
     * 解析为纯 CPU 工作，同步执行；导入中禁止重新解析。
     */
    fun parse() {
        if (runState.value.importing) return
        val parsed = try {
            parseText(inputs.value.input)
        } catch (e: IllegalArgumentException) {
            inputs.update {
                it.copy(files = emptyList(), parseError = e.message?.takeIf(String::isNotBlank) ?: "秒传内容格式不正确，无法解析")
            }
            return
        }
        inputs.update {
            it.copy(
                files = parsed,
                parseError = if (parsed.isEmpty()) "未解析到有效文件，请检查秒传内容" else null,
            )
        }
    }

    /** 目标目录（DirectoryPickerDialog 确认回传）；0 = 根目录。 */
    fun setTargetDirectory(dirId: Long) {
        inputs.update { it.copy(parentDirId = dirId) }
    }

    /** 开始导入：对解析快照执行，进度经仓库 progress 流透传，完成后落到汇总。 */
    fun startImport() {
        if (runState.value.importing) return
        val files = inputs.value.files
        if (files.isEmpty()) return
        cancelFlag.set(false)
        runState.value = Run(importing = true)
        val parentDirId = inputs.value.parentDirId
        viewModelScope.launch {
            var report: RapidReport? = null
            try {
                report = rapid.import(files, parentDirId) { cancelFlag.get() }
            } finally {
                // 无论正常返回还是取消异常，都必须复位导入态，避免按钮永久卡死。
                runState.update { it.copy(importing = false) }
            }
            if (report != null) {
                runState.value = Run(report = report)
            }
        }
    }

    /** 请求取消：仅置协作标志，由导入循环在下个文件边界自行退出并回传部分汇总。 */
    fun requestCancel() {
        val current = runState.value
        if (!current.importing || current.cancelRequested) return
        cancelFlag.set(true)
        runState.update { it.copy(cancelRequested = true) }
    }

    /** 清空回输入态（文本 / 解析结果 / 目标目录 / 运行态一并复位）。 */
    fun reset() {
        cancelFlag.set(false)
        inputs.value = Inputs()
        runState.value = Run()
    }
}
