package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFormatted
import com.aliothmoon.maafw.util.HttpClientHelper
import com.aliothmoon.maafw.util.readBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 远程资源包清单（发布在仓库 main 分支根目录的 pi-manifest.json）
 *
 * 与 APK 版本解耦：APK 里打包的 PI 是发布那一刻的快照，上游 AALC 换图换流程后
 * 不必等下一次发版，App 自己取这份清单判断要不要下新的 pi.zip
 */
@Serializable
data class PiRemoteManifest(
    /** 展示用版本号，例如 "1.6.2" */
    val version: String = "",

    /** 比较用整数，必须严格递增；与 [PiInstaller] 的 pi.version 同一标尺 */
    val versionCode: Int = 0,

    /** pi.zip 的直链（GitHub Release asset 或我们仓库的 raw 大文件） */
    val url: String = "",

    /** pi.zip 的 SHA-256，十六进制，大小写不敏感 */
    val sha256: String = "",

    /** 期望体积，仅用于下载进度；缺失时按 Content-Length */
    val sizeBytes: Long = 0,

    /** 这份资源对应的上游 AALC tag，例如 "V1.6.2"；只做展示 */
    val aalcTag: String = "",

    /** 变更说明，可空 */
    val notes: String = "",
)

sealed interface PiRemoteState {
    data object Idle : PiRemoteState

    data object Checking : PiRemoteState

    /** 已经是最新，不必下载 */
    data class UpToDate(val version: String) : PiRemoteState

    data class Downloading(val percent: Int) : PiRemoteState

    data object Installing : PiRemoteState

    data class Installed(val version: String) : PiRemoteState

    data class Failed(val reason: UiText) : PiRemoteState
}

/**
 * 远程资源更新在 VM 侧的可见面
 *
 * 单独抽接口只为测试能塞空实现：VM 的构造点太多，为一个启动后台任务拖动 HttpClientHelper
 * 与文件系统进来不划算
 */
interface PiResourceUpdater {
    val state: StateFlow<PiRemoteState>

    /** 检查并（必要时）安装；返回是否真的装了新的一份 */
    suspend fun checkAndInstall(): Boolean
}

/**
 * PI 资源包的远程更新
 *
 * 分工：清单与安装包由 CI 发布（.github/workflows/publish-pi.yml 打 pi.zip + pi-manifest.json），
 * 这里只做「取清单 → 比版本 → 下载 → 校验 → 交给 [PiInstaller.installPackage] 原子替换」。
 *
 * 失败一律保留旧版：暂存目录解包成功才换目录，见 [PiInstaller.installPackage]
 */
class PiRemoteUpdater(
    private val helper: HttpClientHelper,
    private val installer: PiInstaller,
    private val manifestUrls: List<String> = PiRemoteUpdater.DEFAULT_MANIFEST_URLS,
) : PiResourceUpdater {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _state = MutableStateFlow<PiRemoteState>(PiRemoteState.Idle)
    override val state: StateFlow<PiRemoteState> = _state.asStateFlow()

    private val mutex = Mutex()

    override suspend fun checkAndInstall(): Boolean = mutex.withLock {
        withContext(MaaDispatchers.IO) {
            _state.value = PiRemoteState.Checking
            try {
                val manifest = fetchManifest()
                val installed = installer.installedVersion()
                if (!isNewer(manifest, installed)) {
                    _state.value = PiRemoteState.UpToDate(manifest.version)
                    return@withContext false
                }
                if (manifest.url.isBlank() || manifest.sha256.isBlank()) {
                    throw IOException("manifest has no url/sha256")
                }
                val archive = download(manifest)
                try {
                    _state.value = PiRemoteState.Installing
                    installer.installPackage(ZipFilePiPackage(archive), manifest.versionCode)
                } finally {
                    archive.delete()
                }
                _state.value = PiRemoteState.Installed(manifest.version)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "PI remote update failed")
                _state.value = PiRemoteState.Failed(
                    uiTextFormatted(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName),
                )
                false
            }
        }
    }

    /**
     * 要不要装这份
     *
     * 两套号不能直接比：APK 自带那份的 pi.version 是 APK 的 VERSION_CODE（几万），
     * 远程清单用的是 PI 自己的修订号（个位数）。只有「当前这份本来就是远程装的」时
     * 两者才在同一标尺上；否则任何一份有效清单都比包里的新
     */
    private fun isNewer(manifest: PiRemoteManifest, installed: Int?): Boolean {
        if (manifest.versionCode <= 0) return false
        if (installed == null) return true
        if (!installer.isRemoteInstalled()) return true
        return manifest.versionCode > installed
    }

    private suspend fun fetchManifest(): PiRemoteManifest {
        var lastError: Exception? = null
        for (url in manifestUrls) {
            try {
                val response = helper.get(url)
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException(url.substringAfterLast('/') + " -> HTTP " + response.code)
                }
                return json.decodeFromString(PiRemoteManifest.serializer(), response.readBody())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "manifest source failed: %s", url)
                lastError = e
            }
        }
        throw lastError ?: IOException("no manifest source")
    }

    /** 校验 SHA-256 后返回落在缓存目录里的 zip；调用方负责删 */
    private fun download(manifest: PiRemoteManifest): File {
        val dir = File(AppPaths.ROOT, DOWNLOAD_DIR).apply { mkdirs() }
        val target = File(dir, "pi-" + manifest.versionCode + ".zip")
        var lastError: Exception? = null
        for (url in downloadUrls(manifest.url)) {
            try {
                target.delete()
                fetchTo(url, target, manifest) { percent -> _state.value = PiRemoteState.Downloading(percent) }
                val actual = sha256(target)
                if (!actual.equals(manifest.sha256.trim(), ignoreCase = true)) {
                    target.delete()
                    throw IOException("sha256 mismatch: " + actual.take(12))
                }
                return target
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                Timber.d(e, "download source failed: %s", url)
                lastError = e
            }
        }
        target.delete()
        throw lastError ?: IOException("download failed")
    }

    private fun fetchTo(url: String, target: File, manifest: PiRemoteManifest, onPercent: (Int) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        helper.rawClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(url.substringAfterLast('/') + " -> HTTP " + response.code)
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: manifest.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onPercent(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val builder = StringBuilder(64)
        for (byte in digest.digest()) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    /** 直连失败时按镜像前缀再试一遍；国内直连 raw/release 常被卡住 */
    private fun downloadUrls(url: String): List<String> {
        if (!url.startsWith("https://github.com/") && !url.startsWith("https://raw.githubusercontent.com/")) {
            return listOf(url)
        }
        return listOf(url) + MIRROR_PREFIXES.map { it + url }
    }

    companion object {
        private const val DOWNLOAD_DIR = "pi.download"
        private const val COPY_BUFFER_BYTES = 128 * 1024

        /** 清单地址：raw 直连 + 两个常用加速前缀，先说 raw 是为了海外/直连能走通 */
        val DEFAULT_MANIFEST_URLS: List<String> = listOf(
            "https://raw.githubusercontent.com/Zhonz/AALC-Meow-AI/main/pi-manifest.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/Zhonz/AALC-Meow-AI/main/pi-manifest.json",
            "https://cdn.jsdelivr.net/gh/Zhonz/AALC-Meow-AI@main/pi-manifest.json",
        )

        private val MIRROR_PREFIXES = listOf(
            "https://gh-proxy.com/",
            "https://ghfast.top/",
        )
    }
}
