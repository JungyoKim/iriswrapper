package party.qwer.iris

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Low-level diagnostics for the send path. Goal: when a send fails, the log alone
// must reveal the root cause (exact exception chain + the environment at that moment),
// and a periodic heartbeat lets us see what drifts over time before the failure.
object SendDiag {
    private val startTime = System.currentTimeMillis()
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun now(): String = ts.format(Date())

    fun uptime(): String {
        val s = (System.currentTimeMillis() - startTime) / 1000
        return "${s / 3600}h${(s % 3600) / 60}m${s % 60}s"
    }

    // KakaoTalk main process state, read straight from /proc (iris runs as root).
    fun kakaoProcInfo(): String {
        val procDir = File("/proc")
        val pids = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?: return "proc-scan-failed"
        for (p in pids) {
            val cmdline = runCatching {
                File(p, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull() ?: continue
            if (cmdline == "com.kakao.talk") {
                val adj = runCatching { File(p, "oom_score_adj").readText().trim() }.getOrDefault("?")
                val state = runCatching {
                    File(p, "stat").readText().substringAfter(") ").firstOrNull()?.toString() ?: "?"
                }.getOrDefault("?")
                return "pid=${p.name} adj=$adj state=$state"
            }
        }
        return "KAKAO_NOT_RUNNING"
    }

    // Full Throwable chain with stack frames, so a wrapped exception still shows its real cause.
    fun throwableChain(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 10) {
            sb.append(if (depth == 0) "EXC: " else "CAUSED BY: ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message).append('\n')
            for (frame in cur.stackTrace.take(15)) {
                sb.append("    at ").append(frame.toString()).append('\n')
            }
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    fun logSendFailure(
        attempt: Int, chatId: Long, threadId: Long?, msg: String, refererArg: String, e: Throwable
    ) {
        val refFresh = runCatching { RefererProvider.getReferer() }
            .map { "len=${it.length} val=$it" }.getOrElse { "READ_FAILED: $it" }
        System.err.println(
            buildString {
                append("\n========== SEND FAILURE DIAG ==========\n")
                append("time=${now()} irisUptime=${uptime()}\n")
                append("attempt=$attempt chatId=$chatId threadId=$threadId msgLen=${msg.length} msgHead=\"${msg.take(20)}\"\n")
                append("refererArg=$refererArg\n")
                append("refererFresh=$refFresh\n")
                append("kakaoProc=${kakaoProcInfo()}\n")
                append("amsBinder=${AndroidHiddenApi.binderStatus()}\n")
                append(throwableChain(e))
                append("=======================================\n")
            }
        )
    }

    fun startHeartbeat() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(10 * 60 * 1000)
                    val ref = runCatching { RefererProvider.getReferer() }
                        .map { "len=${it.length}" }.getOrElse { "READ_FAILED" }
                    System.err.println(
                        "[HEARTBEAT] time=${now()} uptime=${uptime()} kakao=${kakaoProcInfo()} " +
                            "ams=${AndroidHiddenApi.binderStatus()} referer=$ref"
                    )
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; name = "send-diag-heartbeat"; start() }
        System.err.println("[HEARTBEAT] started (10min interval)")
    }
}
