package com.antigravity.kickclicker.time

import android.content.Context
import android.os.SystemClock
import com.antigravity.kickclicker.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.math.abs

/**
 * 毫秒级授时。优先 SNTP（四时间戳、取最小往返时延样本），失败降级到 HTTP Date 头（仅秒级精度）。
 * 时钟推进使用 elapsedRealtime 单调锚点，避免系统墙钟被 NTP/手动调整时倒计时跳变。
 */
object TimeSyncManager {

    enum class Source {
        LOCAL, NTP, HTTP;

        companion object {
            fun fromName(name: String?): Source = entries.firstOrNull { it.name == name } ?: HTTP
        }
    }

    private const val NTP_EPOCH_OFFSET_SECONDS = 2208988800L
    private const val NTP_PACKET_SIZE = 48
    private const val BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000L
    private const val STALE_MILLIS = 6 * 60 * 60 * 1000L
    private const val REANCHOR_THRESHOLD_MS = 3000L
    private const val NTP_READ_TIMEOUT_MS = 1500
    private const val NTP_SAMPLES_PER_HOST = 2
    private const val NTP_BUDGET_MS = 6000L
    private const val HTTP_BUDGET_MS = 4000L

    private val ntpHosts = listOf("ntp.aliyun.com", "cn.pool.ntp.org", "pool.ntp.org", "time.apple.com")
    private val httpFallbacks = listOf("https://www.baidu.com", "https://www.taobao.com", "https://www.qq.com")

    private val twoDigits = Array(100) { if (it < 10) "0$it" else it.toString() }

    @Volatile
    var offsetMillis: Long = 0L
        private set

    @Volatile
    var source: Source = Source.LOCAL
        private set

    @Volatile
    var syncedAtWallMillis: Long = 0L
        private set

    @Volatile
    private var syncing = false

    @Volatile
    private var appContext: Context? = null

    private var anchorElapsed = SystemClock.elapsedRealtime()
    private var anchorWall = System.currentTimeMillis()

    /** 冷启动复用上次校准结果（未过期时），避免每次都要手动对时。 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        if (appContext === ctx) return
        appContext = ctx
        val state = AppPrefs.loadTimeState(ctx)
        if (state.syncedAtMillis > 0L && state.offsetMillis != 0L &&
            System.currentTimeMillis() - state.syncedAtMillis < STALE_MILLIS
        ) {
            offsetMillis = state.offsetMillis
            syncedAtWallMillis = state.syncedAtMillis
            source = Source.fromName(state.source)
            reanchor()
        }
    }

    val isSynchronized: Boolean
        get() = source != Source.LOCAL && syncedAtWallMillis > 0 &&
            System.currentTimeMillis() - syncedAtWallMillis < STALE_MILLIS

    val syncAgeMillis: Long?
        get() = if (syncedAtWallMillis <= 0L) null
        else (System.currentTimeMillis() - syncedAtWallMillis).coerceAtLeast(0L)

    /** 并发去抖：同一时刻只有一次真实网络请求。 */
    suspend fun syncWithNetwork(): Result<Long> {
        if (syncing) return Result.failure(IllegalStateException("对时进行中，请稍候"))
        syncing = true
        try {
            val outcome = withTimeoutOrNull(NTP_BUDGET_MS) { ntpRound() }
                ?: withTimeoutOrNull(HTTP_BUDGET_MS) { httpRound() }
            if (outcome == null) return Result.failure(IllegalStateException("无法连接授时服务器"))
            applyOffset(outcome.offset, outcome.source)
            return Result.success(offsetMillis)
        } finally {
            syncing = false
        }
    }

    private data class Timing(val offset: Long, val delay: Long, val source: Source)

    private suspend fun ntpRound(): Timing? = withContext(Dispatchers.IO) {
        var best: Timing? = null
        for (host in ntpHosts) {
            repeat(NTP_SAMPLES_PER_HOST) {
                val sample = queryNtp(host)
                if (sample != null && (best == null || sample.delay < best!!.delay)) best = sample
            }
            if (best != null) return@withContext best
        }
        best
    }

    private suspend fun httpRound(): Timing? = withContext(Dispatchers.IO) {
        for (target in httpFallbacks) {
            try {
                val start = System.currentTimeMillis()
                val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 2000
                    readTimeout = 2000
                    instanceFollowRedirects = false
                }
                conn.connect()
                val serverDate = conn.date
                val end = System.currentTimeMillis()
                val latency = end - start
                conn.disconnect()
                // HTTP Date 头只有秒级分辨率，仅作为 SNTP 不可用时的降级
                if (serverDate > 0) return@withContext Timing(
                    serverDate + latency / 2 - end, latency, Source.HTTP
                )
            } catch (_: Exception) {
            }
        }
        null
    }

    private suspend fun queryNtp(host: String): Timing? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            socket = DatagramSocket().apply { soTimeout = NTP_READ_TIMEOUT_MS }
            val packet = ByteArray(NTP_PACKET_SIZE)
            packet[0] = 0x1B // LI=3, VN=3, Mode=3(client)
            val t0 = System.currentTimeMillis()
            writeClientTransmit(packet, t0)
            socket.send(DatagramPacket(packet, packet.size, address, 123))

            val buffer = ByteArray(NTP_PACKET_SIZE)
            socket.receive(DatagramPacket(buffer, buffer.size))
            val t3 = System.currentTimeMillis()

            val t1 = toEpochMillis(readUnsigned32(buffer, 32), readUnsigned32(buffer, 36))
            val t2 = toEpochMillis(readUnsigned32(buffer, 40), readUnsigned32(buffer, 44))
            if (t1 <= 0L || t2 <= 0L) return@withContext null

            val offset = ((t1 - t0) + (t2 - t3)) / 2
            val delay = (t3 - t0) - (t2 - t1)
            // 偏差超过 10 小时视为异常报文，避免把设备时间拉到不可用位置
            if (delay < 0 || delay > 5000 || abs(offset) > 10 * 60 * 60 * 1000L) return@withContext null
            Timing(offset, delay, Source.NTP)
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun readUnsigned32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun toEpochMillis(seconds: Long, fraction: Long): Long {
        if (seconds <= NTP_EPOCH_OFFSET_SECONDS) return 0L
        return (seconds - NTP_EPOCH_OFFSET_SECONDS) * 1000L + ((fraction * 1000L) shr 32)
    }

    private fun writeClientTransmit(packet: ByteArray, epochMillis: Long) {
        val ntpSeconds = (epochMillis / 1000L) + NTP_EPOCH_OFFSET_SECONDS
        val millisOfDay = ((epochMillis % 1000L) + 1000L) % 1000L
        val fraction = (millisOfDay shl 32) / 1000L and 0xFFFFFFFFL
        packet[40] = (ntpSeconds shr 24).toByte()
        packet[41] = (ntpSeconds shr 16).toByte()
        packet[42] = (ntpSeconds shr 8).toByte()
        packet[43] = ntpSeconds.toByte()
        packet[44] = (fraction shr 24).toByte()
        packet[45] = (fraction shr 16).toByte()
        packet[46] = (fraction shr 8).toByte()
        packet[47] = fraction.toByte()
    }

    private fun applyOffset(offset: Long, from: Source) {
        offsetMillis = offset
        source = from
        syncedAtWallMillis = System.currentTimeMillis()
        reanchor()
        appContext?.let { AppPrefs.saveTimeOffset(it, offsetMillis, syncedAtWallMillis, from.name) }
    }

    private fun reanchor() {
        anchorElapsed = SystemClock.elapsedRealtime()
        anchorWall = System.currentTimeMillis() + offsetMillis
    }

    /** 校准后的 epoch 毫秒；墙钟被步进时自动重锚定。 */
    fun nowMillis(): Long {
        val monotonic = anchorWall + (SystemClock.elapsedRealtime() - anchorElapsed)
        val wall = System.currentTimeMillis() + offsetMillis
        if (abs(monotonic - wall) > REANCHOR_THRESHOLD_MS) {
            reanchor()
            return wall
        }
        return monotonic
    }

    fun remainingTo(targetTimeMillis: Long): Long = targetTimeMillis - nowMillis()

    fun formatCurrentClock(): String = formatClockMillis(nowMillis())

    /** epoch 毫秒 → 北京时间 HH:mm:ss.SSS，无 Calendar/SimpleDateFormat 分配。 */
    fun formatClockMillis(epochMillis: Long): String = formatTimeOfDay(epochMillis + BEIJING_OFFSET_MS)

    /** 倒计时展示：这是时长不是时刻，不能再叠加时区偏移。 */
    fun formatCountdown(remainingMillis: Long): String =
        formatTimeOfDay(remainingMillis.coerceAtLeast(0L))

    private fun formatTimeOfDay(rawMillis: Long): String {
        var total = if (rawMillis < 0L) 0L else rawMillis
        val ms = (total % 1000).toInt()
        total /= 1000
        val s = (total % 60).toInt()
        total /= 60
        val m = (total % 60).toInt()
        total /= 60
        val h = (total % 24).toInt()
        return twoDigits[h] + ":" + twoDigits[m] + ":" + twoDigits[s] + "." +
            (ms / 100).toString() + twoDigits[ms % 100]
    }

    fun statusLabel(): String {
        if (!isSynchronized) return "未校准（使用本机时钟）"
        val age = syncAgeMillis ?: 0L
        val prefix = if (source == Source.NTP) "SNTP 已校准" else "HTTP 秒级校准"
        return "$prefix · ${age / 60000}分钟前"
    }
}
