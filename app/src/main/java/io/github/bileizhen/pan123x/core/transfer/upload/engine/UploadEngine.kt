package io.github.bileizhen.pan123x.core.transfer.upload.engine

import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanUploadApi
import io.github.bileizhen.pan123x.core.network.UploadRequestDto
import io.github.bileizhen.pan123x.core.transfer.upload.RandomAccessReader
import io.github.bileizhen.pan123x.core.transfer.upload.UploadPartPlan
import io.github.bileizhen.pan123x.core.transfer.upload.UploadSource
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 同名冲突处理策略；[duplicate] 即 `upload_request` body 的 `duplicate` 字段
 * （0=提示交上层询问、1=保留两者、2=覆盖；参考源 `up_load` 的 `dup_choice`）。
 */
enum class ConflictPolicy(val duplicate: Int) { ASK(0), KEEP_BOTH(1), OVERWRITE(2) }

/**
 * 持久化的 S3 multipart 会话（末段，对应 `data.Bucket` / `data.StorageNode` /
 * `data.Key` / `data.UploadId` / `data.FileId`，upload_service.py:340-344）。
 *
 * [etag] 为本地文件 MD5（参考源 session 回调里的 `readable_hash`，:356）；
 * [blockSize] 为该会话的分片大小（参考源固定 5242880，:267）——续传时从任务行还原，
 * 因此引擎不写死、但新建会话一律用 [UploadPartPlan.BLOCK_SIZE]。
 *
 * 字段名与 UploadCoordinator 的落库/还原编码一一对应（接口约定，不得改名）。
 */
data class UploadSession(
    val bucket: String,
    val storageNode: String,
    val uploadKey: String,
    val uploadId: String,
    val fileId: Long,
    val etag: String,
    val blockSize: Long,
)

/** 一次上传执行请求。[key] 为任务标识（taskId），仅用于日志（不得是 URL/凭据）。 */
data class UploadRequest(
    val key: String,
    val source: UploadSource,
    val parentFileId: Long,
    val policy: ConflictPolicy,
    /**
     * 断点会话；null 或来源校验不通过（，由协调器负责 size/mtime 校验）则从头走完整流程。
     * 任务身份是 FileId/size/etag，预签名 URL 短期有效、绝不作为身份持久化（同理）。
     */
    val resumeSession: UploadSession? = null,
)

/** 单个分片的快照（1-based 分片号 + 实际字节长度），供协调器镜像到 Room（PLAN ：分片粒度断点）。 */
data class UploadPartSnapshot(val partNumber: Int, val size: Int)

/** 引擎执行结果。冲突需上层询问用户后带 KEEP_BOTH/OVERWRITE 重跑（UploadCoordinator.resolveConflict）。 */
sealed interface UploadOutcome {
    /** [reused]=true 表示秒传命中（`data.Reuse`，upload_service.py:328-338），未上传任何字节。 */
    data class Done(val fileId: Long, val reused: Boolean) : UploadOutcome

    /** 同名冲突且策略为 ASK：需上层询问用户后带 1/2 重跑。 */
    data class Conflict(val serverMessage: String) : UploadOutcome

    /** 契约完备性保留；引擎的取消以 [CancellationException] 传播，自身不会返回它。 */
    data object Canceled : UploadOutcome
}

/**
 * 上传引擎：把 [UploadSource] 的字节送上 S3 multipart 会话，
 * 编排顺序对齐参考源 `.reference/123pan` `src/app/service/upload_service.py` 的 `up_load`：
 *
 * 1. `resumeSession != null`（协调器已按  校验 size/mtime）：跳过 MD5 与 `upload_request`，
 *    直接 `s3_list_upload_parts` 取已传分片（:365-393）；list 失败则放弃会话、从头走完整流程；
 * 2. 完整流程：[Md5Hasher] 计算 MD5 → `upload_request`（duplicate=[ConflictPolicy.duplicate]）：
 *    `conflict` 且 ASK → 返回 [UploadOutcome.Conflict]，不发任何 PUT；`conflict` 且用户已选
 *    1/2 → 带值重发一次（参考源 duplicate_callback，:313-323）；再次 conflict 仍返回 Conflict；
 *    `reuse` → 秒传 [UploadOutcome.Done]（reused=true），不建会话不 PUT（:328-338）；
 * 3. 会话五字段齐全后**立即**回调 [session]，供协调器先落库再执行（*    参考源 session_callback，:346-363）；秒传/冲突不回调；
 * 4. 分片：list parts → 完整计划回调 [parts]（全部分片）→ 续传起点回调 [progress] →
 *    逐片"窗口预签名（跨 worker 缓存，缺失单分片兜底）→ PUT"；预签名 URL 缓存对齐参考源
 *    `_get_presigned_url`（:427-452），单分片兜底对齐 `_fetch_single_presigned_url`（:174-185）；
 * 5. 收尾：再次 list 确认 → `s3_complete_multipart_upload` → size > 64 MiB 时
 *    delay(3s)（:569-570 的可取消等价）→ `upload_complete` → [UploadOutcome.Done]。
 *
 * 并发模型：协程并发（`Semaphore` + `async`），**不新建线程池**。分片协程继承
 * 调用方上下文——生产环境由协调器挂在应用级 IO 调度器 scope（见 UploadCoordinator KDoc），
 * 阻塞网络在 [OkHttpUploadPartTransport] 内部切 IO；继承上下文同时保证单测在 TestDispatcher
 * 下完全确定（与协调器同一决策）。分片 PUT 重试：最多 5 次、退避 `min(2^(n-1), 4)` 秒
 * （[delay] 可取消）、**只重试**超时/连接类 [IOException]（参考源 `_put_part_with_retry`，
 * :187-225；Timeout/ConnectionError 映射为 SocketTimeoutException / ConnectException /
 * UnknownHostException）。
 *
 * 取消：协程取消立即传播（[CancellationException] 不吞不包装）。
 * 元数据失败（非 0 code / 网络 / 解析）抛 [IOException]（消息含服务端 code/message 供日志），
 * 用户可读文案由协调器经 `UploadMessages.transferFailure` 统一映射。
 * 日志只记文件名 / 分片号 / 字节数，绝不记录预签名 URL、query 与 token。
 *
 * 引擎不碰 Room / 通知 / Android Context（PLAN）。
 */
class UploadEngine(
    private val api: PanUploadApi,
    private val transport: UploadPartTransport,
    private val logger: AppLogger? = null,
    private val threads: Int = 1,
    private val threadsProvider: () -> Int = { threads },
    private val retriesEnabled: () -> Boolean = { true },
) {
    // Android memory budget: eight 5 MB parts across all uploads, independent of task count.
    private val globalPartSlots = Semaphore(8)

    /**
     * 完整上传流程。取消靠协程取消（抛 [CancellationException]，不吞）。
     *
     * @param progress `(uploaded, total)`；首次以续传起点（已传分片字节）调用一次，
     *   之后每完成一个分片以累计字节调用一次（对齐参考源 :396-416 的 total_sent/首次上报）。
     * @param session 获得 S3 会话后回调恰一次，供上层立即持久化（先落库再执行）；
     *   续传路径与秒传/冲突不回调。
     * @param parts 分片计划快照回调：计划确定时以**完整计划**（全部分片）调用一次，
     *   此后每完成一个分片以**单元素列表**调用。
     * @throws IOException 传输/校验/元数据失败；协程取消直接传播。
     */
    suspend fun upload(
        request: UploadRequest,
        progress: (uploaded: Long, total: Long) -> Unit = { _, _ -> },
        session: (UploadSession) -> Unit = {},
        parts: (List<UploadPartSnapshot>) -> Unit = {},
    ): UploadOutcome {
        val source = request.source
        val size = source.size
        logger?.i(LogSource.UPLOAD, "上传开始：${source.displayName}（key=${request.key}，$size 字节）")

        // ---- 1. 断点续传：来源 size/mtime 校验已由协调器完成，这里只向服务端确认会话仍可用 ----
        var uploadedParts: Set<Int> = emptySet()
        val resumed = request.resumeSession?.let { resume ->
            val listed = api.listUploadedParts(resume.bucket, resume.uploadKey, resume.uploadId, resume.storageNode)
            if (listed is ApiResult.Success) {
                uploadedParts = listed.data.toSet()
                if (listed.data.isNotEmpty()) {
                    logger?.i(LogSource.UPLOAD, "断点续传：${source.displayName}，服务端已确认 ${listed.data.size} 片")
                }
                resume
            } else {
                // list 失败即放弃会话、从头走完整流程（宁可重算 MD5，不放弃上传）
                logger?.w(LogSource.UPLOAD, "续传会话确认失败，放弃会话重走完整流程：${source.displayName}")
                null
            }
        }

        // ---- 2. 完整流程：MD5 → upload_request（upload_service.py:287-344）----
        val active: UploadSession = resumed ?: run {
            val etag = source.openStream().use { stream -> Md5Hasher.hash(stream, size) }
            logger?.i(LogSource.UPLOAD, "MD5 计算完成：${source.displayName}")
            when (val resolution = resolveUploadRequest(request, etag)) {
                is RequestResolution.Reused -> {
                    logger?.i(LogSource.UPLOAD, "秒传命中：${source.displayName}（fileId=${resolution.fileId}）")
                    return UploadOutcome.Done(fileId = resolution.fileId, reused = true)
                }
                is RequestResolution.Conflict -> {
                    logger?.i(LogSource.UPLOAD, "同名文件冲突，等待用户选择：${source.displayName}")
                    return UploadOutcome.Conflict(serverMessage = resolution.serverMessage)
                }
                is RequestResolution.Ready -> {
                    val dto = resolution.dto
                    val created = UploadSession(
                        bucket = dto.bucket,
                        storageNode = dto.storageNode,
                        uploadKey = dto.key,
                        uploadId = dto.uploadId,
                        fileId = dto.fileId,
                        etag = etag,
                        blockSize = UploadPartPlan.BLOCK_SIZE, // 参考源 :267 固定 5 MiB
                    )
                    // 会话立即回调：协调器在回调内先落库再继续（参考源 :346-363）
                    session(created)
                    logger?.i(LogSource.UPLOAD, "已获得 S3 会话：${source.displayName}（fileId=${created.fileId}）")
                    created
                }
            }
        }

        // ---- 3. 必调传输列表（参考源 :368-381，失败即抛"获取传输列表"）：会话建立后先
        // s3_list_upload_parts 取权威已传分片集合，新会话通常为空表。续传路径的步骤 1 探测
        // 就是同一次调用（结果已写入 uploadedParts），这里不再重复发——与参考源"上传前恰好
        // 一次 list + 收尾确认一次"的节奏一致。
        if (resumed == null) {
            uploadedParts = api
                .listUploadedParts(active.bucket, active.uploadKey, active.uploadId, active.storageNode)
                .requireSuccess("获取传输列表")
                .toSet()
        }

        // ---- 4. 分片计划与起点回调（对齐参考源 :388-416 的计算顺序）----
        val blockSize = if (active.blockSize > 0) active.blockSize else UploadPartPlan.BLOCK_SIZE
        val totalParts = UploadPartPlan.totalParts(size, blockSize)
        val plan = (1..totalParts).map { partNumber ->
            UploadPartSnapshot(partNumber, UploadPartPlan.lengthOf(partNumber, size, blockSize))
        }
        parts(plan) // 首次回调 = 完整计划（含已传分片，供协调器整体镜像到 Room）
        progress(UploadPartPlan.uploadedBytes(uploadedParts, size, blockSize), size) // 首次 = 续传起点

        uploadPendingParts(source, active, size, blockSize, totalParts, uploadedParts, progress, parts)
        return settleSession(active, size, source.displayName)
    }

    // ---- upload_request：冲突分派 ----

    /** [resolveUploadRequest] 的内部结局（对应参考源 :312-338 的 code 分派）。 */
    private sealed interface RequestResolution {
        data class Ready(val dto: UploadRequestDto) : RequestResolution
        data class Reused(val fileId: Long) : RequestResolution
        data class Conflict(val serverMessage: String) : RequestResolution
    }

    /**
     * 申请上传。首次即带 [ConflictPolicy.duplicate]（与参考源先 0 后重发等价：ASK=0 首发即
     * 参考源的 `duplicate: 0`；用户已选 1/2 时直接带值，省去一次注定冲突的往返）。
     * 冲突且非 ASK 时带同值重发一次（参考源 duplicate_callback，:313-323）；再次冲突原样返回。
     */
    private suspend fun resolveUploadRequest(request: UploadRequest, etag: String): RequestResolution {
        val source = request.source
        fun asResolution(dto: UploadRequestDto): RequestResolution = when {
            dto.conflict -> RequestResolution.Conflict(dto.serverMessage)
            dto.reuse -> RequestResolution.Reused(dto.fileId)
            else -> RequestResolution.Ready(dto)
        }
        val first = asResolution(
            api.requestUpload(
                fileName = source.displayName,
                size = source.size,
                etag = etag,
                parentFileId = request.parentFileId,
                duplicate = request.policy.duplicate,
            ).requireSuccess("upload_request"),
        )
        if (first !is RequestResolution.Conflict || request.policy == ConflictPolicy.ASK) {
            return first
        }
        logger?.i(
            LogSource.UPLOAD,
            "同名冲突，按用户选择 duplicate=${request.policy.duplicate} 重发：${source.displayName}",
        )
        return asResolution(
            api.requestUpload(
                fileName = source.displayName,
                size = source.size,
                etag = etag,
                parentFileId = request.parentFileId,
                duplicate = request.policy.duplicate,
            ).requireSuccess("upload_request"),
        )
    }

    // ---- 分片并发上传 ----

    /**
     * 上传全部待传分片。来源支持随机读 → [UploadPartPlan.workerCount] 个并发 worker；
     * 不支持 → 强制单 worker 顺序消费（`openStream` + 跳过未传前缀）。
     * 任一分片最终失败（不可重试 / 重试耗尽）→ 整个上传抛异常，由 `coroutineScope` 取消其余 worker。
     */
    private suspend fun uploadPendingParts(
        source: UploadSource,
        session: UploadSession,
        size: Long,
        blockSize: Long,
        totalParts: Int,
        uploadedParts: Set<Int>,
        progress: (Long, Long) -> Unit,
        parts: (List<UploadPartSnapshot>) -> Unit,
    ) {
        val pending = UploadPartPlan.pendingParts(totalParts, uploadedParts)
        if (pending.isEmpty()) {
            // 空文件（totalParts==0）或全部分片已传：跳过循环直接收尾（空文件 0 片）
            return
        }

        val randomAccess = source.openRandomAccess()
        val workers = if (randomAccess != null) UploadPartPlan.workerCount(threadsProvider(), pending.size) else 1
        logger?.i(LogSource.UPLOAD, "${source.displayName} 待传 ${pending.size} 片，并发 $workers")
        val reader: PartReader = if (randomAccess != null) {
            RandomPartReader(randomAccess)
        } else {
            SequentialPartReader(source.openStream())
        }
        val totalSent = AtomicLong(UploadPartPlan.uploadedBytes(uploadedParts, size, blockSize))
        val presigner = PresignCache(api, session, totalParts, threads = workers, logger = logger)
        try {
            coroutineScope {
                val gate = Semaphore(workers)
                // 协程并发：每个待传分片一个协程，Semaphore 限制同时在途数（对应参考源
                // ThreadPoolExecutor(max_workers=workers) 的协程等价物，:492-494）
                pending.map { partNumber ->
                    async {
                        gate.withPermit {
                            globalPartSlots.withPermit {
                                uploadOnePart(partNumber, reader, presigner, session, size, blockSize, totalSent, progress, parts)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            try {
                reader.close()
            } catch (closeError: IOException) {
                logger?.w(LogSource.UPLOAD, "关闭上传来源读取句柄失败：${closeError.message}")
            }
        }
    }

    /** 上传单个分片：读字节 → 预签名一次 → 带重试 PUT → 累计进度并回调。 */
    private suspend fun uploadOnePart(
        partNumber: Int,
        reader: PartReader,
        presigner: PresignCache,
        session: UploadSession,
        size: Long,
        blockSize: Long,
        totalSent: AtomicLong,
        progress: (Long, Long) -> Unit,
        parts: (List<UploadPartSnapshot>) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val offset = UploadPartPlan.offsetOf(partNumber, blockSize)
        val length = UploadPartPlan.lengthOf(partNumber, size, blockSize)
        // 读取失败不参与重试：本地来源问题重试无意义（参考源读取同样在重试循环之外，:463-467）
        val data = reader.read(partNumber, offset, length)
        if (data.size != length) {
            throw IOException("分片 $partNumber 读取长度不符：期望 $length 字节，实际 ${data.size} 字节")
        }
        // 预签名一次，重试沿用同一 URL（参考源 :469/:537 在 _put_part_with_retry 之外获取）
        val url = presigner.urlFor(partNumber)
        putWithRetry(partNumber, url, data)
        // 先记分片、再报进度：进度出现时该分片行已存在于协调器的 Room 镜像（不变式，
        // 也让单线程下的回调次序确定可测——参考源 :536-538 是先累计后 report，进度语义不变）。
        parts(listOf(UploadPartSnapshot(partNumber, length)))
        val sent = totalSent.addAndGet(length.toLong())
        progress(sent, size)
    }

    /**
     * 带退避的分片 PUT：最多 [PART_RETRIES] 次；退避 `min(2^(n-1), 4)` 秒（参考源 :212），
     * 用 [delay] 等待、感知取消（参考源 :220-224 的可取消 sleep 等价）；**只重试**
     * 超时/连接类错误，其余 [IOException]（含非 2xx 的 [UploadPartHttpException]）立即抛出。
     */
    private suspend fun putWithRetry(partNumber: Int, url: String, data: ByteArray) {
        var attempt = 1
        while (true) {
            try {
                transport.put(url, data, PART_TIMEOUT_SECONDS)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                if (!retriesEnabled() || attempt >= PART_RETRIES || !isTransient(error)) {
                    throw error
                }
                val backoffSeconds = backoffSeconds(attempt)
                logger?.w(
                    LogSource.UPLOAD,
                    "分片 $partNumber 第 $attempt/$PART_RETRIES 次传输失败（${data.size} 字节），退避 ${backoffSeconds}s 后重试",
                )
                delay(backoffSeconds * 1000L)
                attempt++
            }
        }
    }

    // ---- 收尾（，upload_service.py:547-580） ----

    private suspend fun settleSession(session: UploadSession, size: Long, displayName: String): UploadOutcome.Done {
        // 再次 list 确认（参考源仅校验 code==0，分片完整性由服务端保证，:553-559）
        api.listUploadedParts(session.bucket, session.uploadKey, session.uploadId, session.storageNode)
            .requireSuccess("s3_list_upload_parts")
        api.completeMultipartUpload(session.bucket, session.uploadKey, session.uploadId, session.storageNode)
            .requireSuccess("s3_complete_multipart_upload")
        logger?.i(LogSource.UPLOAD, "分片合并完成，确认上传结果：$displayName")
        if (size > UploadPartPlan.LARGE_FILE_THRESHOLD) {
            // 大文件收尾延迟（参考源 :569-570 sleep(3)；用可取消 delay 等价， 感知取消）
            delay(UploadPartPlan.LARGE_FILE_SETTLE_MS)
        }
        api.finishUpload(session.fileId).requireSuccess("upload_complete")
        logger?.i(LogSource.UPLOAD, "上传完成：$displayName（fileId=${session.fileId}）")
        return UploadOutcome.Done(fileId = session.fileId, reused = false)
    }

    private fun isTransient(error: IOException): Boolean = when (error) {
        // 参考源 :203-208 只重试 Timeout / ConnectionError；
        // UnknownHostException 属于 ConnectionError 家族（DNS 解析失败）。
        is SocketTimeoutException, is ConnectException, is UnknownHostException -> true
        else -> false
    }

    private companion object {
        /** 单次分片 PUT 超时秒数（参考源 `_UPLOAD_PART_TIMEOUT = 120`，upload_service.py:26）。 */
        const val PART_TIMEOUT_SECONDS = 120

        /** 分片最大尝试次数（参考源 `_UPLOAD_PART_RETRIES = 5`，upload_service.py:27）。 */
        const val PART_RETRIES = 5
    }
}

/** 分片重试退避：`min(2^(attempt-1), 4)` 秒（upload_service.py:212）。 */
private fun backoffSeconds(failedAttempts: Int): Long =
    (1L shl (failedAttempts - 1)).coerceAtMost(4L)

/**
 * 元数据 [ApiResult] → data 或抛 [IOException]。引擎只能以异常上报失败（结果类型不含失败分支），
 * 用户可读文案由协调器经 `UploadMessages` 映射；消息保留服务端 code/message 供日志。
 */
private fun <T> ApiResult<T>.requireSuccess(operation: String): T = when (this) {
    is ApiResult.Success -> data
    is ApiResult.ApiError ->
        throw IOException("$operation 失败：服务器返回 code=$code" + if (message.isBlank()) "" else "，message=$message")
    is ApiResult.NetworkError -> throw IOException("$operation 失败：网络错误（${this.message}）")
    is ApiResult.ParseError -> throw IOException("$operation 失败：响应解析失败（${this.message}）")
    ApiResult.SessionExpired -> throw IOException("$operation 失败：登录状态已失效")
}

/**
 * 预签名 URL 缓存（对齐参考源 `url_cache` + `_get_presigned_url`，upload_service.py:424-452）：
 * 命中缓存直接返回；否则批量预签名 `[pn, min(pn+batch, totalParts+1))` 全量入缓存
 * （`partNumberEnd` 排他，）；批量失败不致命、回退单分片（:436-443）；单分片仍缺该分片
 * 即抛错中止（`_fetch_single_presigned_url` 严格模式，:174-185）。
 *
 * 与参考源一致，网络请求在锁内进行（:429-432 url_lock），避免并发 worker 重复预签名同一窗口。
 */
private class PresignCache(
    private val api: PanUploadApi,
    private val session: UploadSession,
    private val totalParts: Int,
    private val threads: Int,
    private val logger: AppLogger?,
) {
    private val mutex = Mutex()
    private val urls = HashMap<Int, String>()

    suspend fun urlFor(partNumber: Int): String = mutex.withLock {
        urls[partNumber]?.let { return it }
        val window = UploadPartPlan.presignWindow(partNumber, totalParts, threads)
        val batched = api.presignParts(
            bucket = session.bucket,
            key = session.uploadKey,
            uploadId = session.uploadId,
            storageNode = session.storageNode,
            partNumberStart = window.start,
            partNumberEnd = window.endExclusive,
        )
        if (batched is ApiResult.Success) {
            urls.putAll(batched.data)
        } else {
            // 批量失败不致命：参考源捕获后回退单分片（upload_service.py:442-443）
            logger?.w(LogSource.UPLOAD, "批量预签名失败，回退单分片请求：分片 $partNumber")
        }
        urls[partNumber]?.let { return it }
        val single = api.presignParts(
            bucket = session.bucket,
            key = session.uploadKey,
            uploadId = session.uploadId,
            storageNode = session.storageNode,
            partNumberStart = partNumber,
            partNumberEnd = partNumber + 1,
        ).requireSuccess("s3_repare_upload_parts_batch")
        val url = single[partNumber] ?: throw IOException("获取分片 $partNumber 预签名 URL 失败")
        urls[partNumber] = url
        url
    }
}

/** 分片字节读取接缝：随机读（可多 worker 并发）或顺序读（单 worker）。 */
private interface PartReader : Closeable {
    suspend fun read(partNumber: Int, offset: Long, length: Int): ByteArray
}

/**
 * 随机读包装。不切换调度器：读运行在分片协程继承的调用方上下文上（生产环境为协调器的
 * IO scope；引擎编排保持可测试的调用方上下文，真正的阻塞网络在 [OkHttpUploadPartTransport]
 * 内部切 [Dispatchers.IO]）。
 */
private class RandomPartReader(private val delegate: RandomAccessReader) : PartReader {
    override suspend fun read(partNumber: Int, offset: Long, length: Int): ByteArray =
        delegate.readAt(offset, length)

    override fun close() {
        delegate.close()
    }
}

/**
 * 顺序读包装：单流按分片升序消费，`skipFully` 跳过未传前缀（等价参考源顺序分支的
 * `f.seek((part_number - 1) * block_size)`，upload_service.py:505-507）。
 *
 * 注意：不用 `InputStream.skipNBytes`——那是 Java 12 API，minSdk 26 的 Android 运行时缺失，
 * 故手写等价跳过（这也是与任务描述"skipNBytes 到偏移"的唯一有意偏差，原因即运行时可用性）。
 */
private class SequentialPartReader(private val stream: InputStream) : PartReader {
    private var position = 0L

    override suspend fun read(partNumber: Int, offset: Long, length: Int): ByteArray {
        // pendingParts 升序保证 offset 只前进；防御性 require 捕捉调用方违反次序的编程错误
        require(offset >= position) { "顺序读取不能回退：分片 $partNumber 请求偏移 $offset，已消费到 $position" }
        if (offset > position) {
            skipFully(stream, offset - position)
            position = offset
        }
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = stream.read(out, filled, length - filled)
            if (read < 0) {
                throw IOException("来源数据不足：分片 $partNumber 需要 $length 字节，仅读到 $filled 字节（提前到达文件尾）")
            }
            filled += read
        }
        position += length
        return out
    }

    override fun close() {
        stream.close()
    }
}

/** [InputStream.skipNBytes] 的 Java 8 等价实现（skip 返回 0 时逐字节推进以探测 EOF）。 */
private fun skipFully(stream: InputStream, bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (stream.read() < 0) {
                throw IOException("来源数据不足：跳到分片偏移时提前到达文件尾（剩余 $remaining 字节）")
            }
            remaining -= 1
        }
    }
}
