package net.raiuchi.piket

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Local, bounded field log. Nothing is uploaded automatically. */
class DiagnosticsLogger(context: Context) {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val current = File(directory, "piket-diagnostics.jsonl")
    private val previous = File(directory, "piket-diagnostics.previous.jsonl")
    private val export = File(directory, "piket-diagnostics.txt")

    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        synchronized(writeLock) {
            runCatching {
                rotateIfNeeded()
                val row = JSONObject().put("time", System.currentTimeMillis())
                    .put("elapsed_ms", SystemClock.elapsedRealtime())
                    .put("seq", sequence.incrementAndGet()).put("event", name)
                fields.forEach { (key, value) -> row.put(key, value ?: JSONObject.NULL) }
                current.appendText(row.toString() + "\n", Charsets.UTF_8)
            }
        }
    }

    fun exportFile(): File = synchronized(writeLock) {
        runCatching {
            val header = "ПИКЕТ — диагностический журнал\nСоздан: ${java.util.Date()}\n" +
                "Содержит GPS-координаты. Передавайте только разработчику ПИКЕТ.\n\n"
            export.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(header)
                if (previous.exists()) previous.forEachLine(Charsets.UTF_8) { writer.appendLine(it) }
                if (current.exists()) current.forEachLine(Charsets.UTF_8) { writer.appendLine(it) }
            }
            export
        }.getOrElse {
            export.writeText("Не удалось подготовить журнал: ${it.javaClass.simpleName}", Charsets.UTF_8)
            export
        }
    }

    fun clear() = synchronized(writeLock) {
        listOf(current, previous, export).forEach { runCatching { it.delete() } }
        event("log_cleared")
    }

    private fun rotateIfNeeded() {
        if (!current.exists() || current.length() < MAX_BYTES) return
        previous.delete()
        current.renameTo(previous)
    }

    companion object {
        private const val MAX_BYTES = 6_000_000L
        private val writeLock = Any()
        private val sequence = AtomicLong()
        private val crashHandlerInstalled = AtomicBoolean()

        fun installCrashHandler(context: Context) {
            if (!crashHandlerInstalled.compareAndSet(false, true)) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val logger = DiagnosticsLogger(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                logger.event("uncaught_exception", mapOf(
                    "thread" to thread.name, "type" to error.javaClass.name,
                    "message" to error.message,
                    "stack" to error.stackTraceToString().take(12_000)))
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }
}