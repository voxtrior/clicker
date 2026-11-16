package com.xundao.clicker.time

import android.os.SystemClock
import com.xundao.clicker.model.TimeServerSource
import com.xundao.clicker.model.TimeSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 毫秒级多源网络授时同步引擎 (TimeSyncEngine)
 *
 * 核心原理：
 * 1. 支持多达 9 种主流服务器时间源（京东、阿里/淘宝、阿里云NTP、腾讯云NTP、国家授时中心NTSC、拼多多、美团、苏宁、小米）。
 * 2. 每次同步连续进行多次高速探测采样（默认 4 次），选取往返延迟 (RTT) 最小的一组样本计算单程传输补偿：
 *    ServerTimeAtReceive = ServerTimestamp + (RTT / 2)
 * 3. 将校准后的基准时间绑定到单调递增硬件时钟 `SystemClock.elapsedRealtime()`，
 *    即使同步后手机系统时间发生跳变或休眠，也能通过 `nowServerMillis()` 实时获得精确到 1ms 的服务器当前时间。
 */
object TimeSyncEngine {

    private const val NTP_PORT = 123
    private const val NTP_PACKET_SIZE = 48
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_VERSION = 3
    // 1900-01-01 至 1970-01-01 的秒数差
    private const val OFFSET_1900_TO_1970 = 2208988800L

    private val _lastSyncResult = MutableStateFlow(
        TimeSyncResult(
            source = TimeServerSource.JD_API,
            success = false,
            clockOffsetMs = 0L,
            rttMs = 0L,
            syncedElapsedRealtime = SystemClock.elapsedRealtime(),
            syncedServerEpochMs = System.currentTimeMillis(),
            message = "尚未同步（当前使用本机时钟）"
        )
    )
    val lastSyncResult: StateFlow<TimeSyncResult> = _lastSyncResult.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * 获取当前时刻精确到毫秒的服务器时间戳（基于单调时钟 SystemClock.elapsedRealtime() 零漂移推算）
     */
    fun nowServerMillis(): Long {
        val sync = _lastSyncResult.value
        return if (sync.success) {
            val deltaElapsed = SystemClock.elapsedRealtime() - sync.syncedElapsedRealtime
            sync.syncedServerEpochMs + deltaElapsed
        } else {
            System.currentTimeMillis()
        }
    }

    /**
     * 执行多样本 RTT 滤波授时同步
     */
    suspend fun syncWithServer(
        source: TimeServerSource,
        sampleCount: Int = 4
    ): TimeSyncResult = withContext(Dispatchers.IO) {
        if (source == TimeServerSource.LOCAL_SYSTEM) {
            val nowSys = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val localRes = TimeSyncResult(
                source = source,
                success = true,
                clockOffsetMs = 0L,
                rttMs = 0L,
                syncedElapsedRealtime = nowElapsed,
                syncedServerEpochMs = nowSys,
                message = "已锁定本机系统毫秒时钟"
            )
            _lastSyncResult.value = localRes
            return@withContext localRes
        }

        _isSyncing.value = true
        try {
            var bestSample: ProbeSample? = null
            val rounds = sampleCount.coerceIn(2, 6)

            for (i in 0 until rounds) {
                val sample = runCatching { probeSingleSample(source) }.getOrNull()
                if (sample != null) {
                    if (bestSample == null || sample.rttMs < bestSample.rttMs) {
                        bestSample = sample
                    }
                }
            }

            if (bestSample != null) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val currentEstimatedServerMs = bestSample.serverEpochAtT4 + (nowElapsed - bestSample.t4Elapsed)
                val offsetMs = currentEstimatedServerMs - System.currentTimeMillis()
                val sign = if (offsetMs >= 0) "+$offsetMs" else "$offsetMs"
                val result = TimeSyncResult(
                    source = source,
                    success = true,
                    clockOffsetMs = offsetMs,
                    rttMs = bestSample.rttMs,
                    syncedElapsedRealtime = nowElapsed,
                    syncedServerEpochMs = currentEstimatedServerMs,
                    message = "同步成功 | 偏差: ${sign}ms | 最优RTT: ${bestSample.rttMs}ms"
                )
                _lastSyncResult.value = result
                return@withContext result
            } else {
                val failRes = _lastSyncResult.value.copy(
                    source = source,
                    success = false,
                    message = "${source.displayName} 同步超时，请检查网络或切换备选源"
                )
                _lastSyncResult.value = failRes
                return@withContext failRes
            }
        } finally {
            _isSyncing.value = false
        }
    }

    private data class ProbeSample(
        val rttMs: Long,
        val t4Elapsed: Long,
        val serverEpochAtT4: Long
    )

    private fun probeSingleSample(source: TimeServerSource): ProbeSample? {
        return when (source.protocol) {
            "SNTP" -> probeSntpServer(source.endpoint)
            "HTTPS_TAOBAO" -> probeTaobaoTimestamp()
            "HTTPS_JD" -> probeJdTimestamp()
            "HTTPS_PDD" -> probePddTimestamp()
            "HTTPS_MEITUAN" -> probeMeituanTimestamp()
            "HTTPS_SUNING" -> probeSuningTimestamp()
            "HTTPS_XIAOMI" -> probeXiaomiTimestamp()
            else -> probeSntpServer("ntp.aliyun.com")
        }
    }

    /**
     * 1. 标准 UDP SNTP (RFC 4330) 64位高精度授时探测
     */
    private fun probeSntpServer(host: String): ProbeSample? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val address = InetAddress.getByName(host)
            val buffer = ByteArray(NTP_PACKET_SIZE)
            buffer[0] = ((NTP_VERSION shl 3) or NTP_MODE_CLIENT).toByte()

            val t1Sys = System.currentTimeMillis()
            val t1Elapsed = SystemClock.elapsedRealtime()
            writeNtpTimeStamp(buffer, 40, t1Sys)

            val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
            socket.send(request)

            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val t4Elapsed = SystemClock.elapsedRealtime()

            val t2ServerReceive = readNtpTimeStamp(buffer, 32)
            val t3ServerTransmit = readNtpTimeStamp(buffer, 40)

            val totalElapsed = t4Elapsed - t1Elapsed
            val serverProcessing = (t3ServerTransmit - t2ServerReceive).coerceAtLeast(0L)
            val rtt = (totalElapsed - serverProcessing).coerceAtLeast(1L)
            val serverEpochAtT4 = t3ServerTransmit + (rtt / 2L)

            return ProbeSample(
                rttMs = rtt,
                t4Elapsed = t4Elapsed,
                serverEpochAtT4 = serverEpochAtT4
            )
        }
    }

    /**
     * 2. 阿里/淘宝毫秒时间戳接口 (https://acs.m.taobao.com/gw/mtop.common.getTimestamp/)
     * 返回格式: {"api":"mtop.common.getTimestamp","v":"*","ret":["SUCCESS::接口调用成功"],"data":{"t":"1763280000123"}}
     */
    private fun probeTaobaoTimestamp(): ProbeSample? {
        return probeHttpEndpoint(
            urlStr = "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/",
            parser = { body, _ ->
                val obj = JSONObject(body)
                obj.optJSONObject("data")?.optString("t")?.toLongOrNull()
            }
        )
    }

    /**
     * 3. 京东毫秒级时间同步探测
     * 优先解析京东接口返回的毫秒级 JSON 字段 (currentTime2 / serverTime / t)，
     * 若 CDN 节点仅返回 HTTP 响应头，则结合京东服务器 Date 头与高分辨率 RTT 边界对齐。
     */
    private fun probeJdTimestamp(): ProbeSample? {
        // 先探测京东 H5 毫秒时间接口
        val jdUrls = listOf(
            "https://api.m.jd.com/client.action?functionId=queryMaterialProducts&client=wh5",
            "https://a.jd.com//ajax/queryServerData.html",
            "https://api.m.jd.com/"
        )
        for (url in jdUrls) {
            val sample = runCatching {
                probeHttpEndpoint(
                    urlStr = url,
                    parser = { body, conn ->
                        val trimmed = body.trim()
                        var parsedMs: Long? = null
                        if (trimmed.startsWith("{")) {
                            val json = JSONObject(trimmed)
                            parsedMs = json.optLong("currentTime2", 0L).takeIf { it > 1_600_000_000_000L }
                                ?: json.optLong("serverTime", 0L).takeIf { it > 1_600_000_000_000L }
                                ?: json.optLong("t", 0L).takeIf { it > 1_600_000_000_000L }
                        }
                        parsedMs ?: parseHttpDateHeader(conn)
                    }
                )
            }.getOrNull()
            if (sample != null) return sample
        }
        return null
    }

    /**
     * 4. 拼多多服务器毫秒时间接口 (https://api.pinduoduo.com/api/server/_stm)
     * 返回格式: {"server_time":1763280000123}
     */
    private fun probePddTimestamp(): ProbeSample? {
        return probeHttpEndpoint(
            urlStr = "https://api.pinduoduo.com/api/server/_stm",
            parser = { body, conn ->
                val obj = JSONObject(body)
                obj.optLong("server_time", 0L).takeIf { it > 1_600_000_000_000L }
                    ?: parseHttpDateHeader(conn)
            }
        )
    }

    /**
     * 5. 美团服务器毫秒时间接口
     * 返回格式: {"status":0,"data":1763280000123}
     */
    private fun probeMeituanTimestamp(): ProbeSample? {
        return probeHttpEndpoint(
            urlStr = "https://cube.meituan.com/ipromotion/cube/toc/component/base/getServerCurrentTime",
            parser = { body, conn ->
                val obj = JSONObject(body)
                obj.optLong("data", 0L).takeIf { it > 1_600_000_000_000L }
                    ?: parseHttpDateHeader(conn)
            }
        )
    }

    /**
     * 6. 苏宁易购服务器时间接口 (https://quan.suning.com/getSysTime.do)
     * 返回格式: {"sysTime2":"2026-11-16 16:30:00","sysTime1":"20261116163000"}
     */
    private fun probeSuningTimestamp(): ProbeSample? {
        return probeHttpEndpoint(
            urlStr = "https://quan.suning.com/getSysTime.do",
            parser = { body, conn ->
                val obj = JSONObject(body)
                val sysTime2 = obj.optString("sysTime2", "")
                if (sysTime2.isNotEmpty()) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT+8")
                    }
                    sdf.parse(sysTime2)?.time
                } else {
                    parseHttpDateHeader(conn)
                }
            }
        )
    }

    /**
     * 7. 小米商城服务器毫秒时间戳接口 (https://tp.hd.xiaomi.com/gettimestamp)
     * 返回格式: var servertime=1763280000
     */
    private fun probeXiaomiTimestamp(): ProbeSample? {
        return probeHttpEndpoint(
            urlStr = "https://tp.hd.xiaomi.com/gettimestamp",
            parser = { body, conn ->
                val numStr = body.substringAfter("=", "").trim()
                val raw = numStr.toLongOrNull()
                when {
                    raw == null -> parseHttpDateHeader(conn)
                    raw > 1_600_000_000_000L -> raw
                    raw > 1_600_000_000L -> raw * 1000L + 500L
                    else -> parseHttpDateHeader(conn)
                }
            }
        )
    }

    private fun probeHttpEndpoint(
        urlStr: String,
        parser: (String, HttpURLConnection) -> Long?
    ): ProbeSample? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        }
        return try {
            // 先完成连接，测量实际请求-响应 RTT
            val t1Elapsed = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            val t4Elapsed = SystemClock.elapsedRealtime()
            val rtt = (t4Elapsed - t1Elapsed).coerceAtLeast(1L)

            val serverTs = parser(body, conn) ?: return null
            ProbeSample(
                rttMs = rtt,
                t4Elapsed = t4Elapsed,
                serverEpochAtT4 = serverTs + (rtt / 2L)
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHttpDateHeader(conn: HttpURLConnection): Long? {
        val dateHeader = conn.date
        // HTTP Date 精确到秒，补偿半秒均值 + RTT/2
        return if (dateHeader > 1_600_000_000_000L) dateHeader + 500L else null
    }

    private fun readNtpTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = readUnsigned32(buffer, offset)
        val fraction = readUnsigned32(buffer, offset + 4)
        return ((seconds - OFFSET_1900_TO_1970) * 1000L) + ((fraction * 1000L) ushr 32)
    }

    private fun writeNtpTimeStamp(buffer: ByteArray, offset: Int, timeMs: Long) {
        val seconds = (timeMs / 1000L) + OFFSET_1900_TO_1970
        val msRemainder = timeMs % 1000L
        val fraction = (msRemainder shl 32) / 1000L

        writeUnsigned32(buffer, offset, seconds)
        writeUnsigned32(buffer, offset + 4, fraction)
    }

    private fun readUnsigned32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toLong() and 0xFF
        val b1 = buffer[offset + 1].toLong() and 0xFF
        val b2 = buffer[offset + 2].toLong() and 0xFF
        val b3 = buffer[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeUnsigned32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }
}
