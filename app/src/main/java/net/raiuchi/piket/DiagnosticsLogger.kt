package net.raiuchi.piket

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Local, bounded field log. Nothing is uploaded automatically. */
class DiagnosticsLogger(context: Context) {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val current = File(directory, "piket-diagnostics.jsonl")
    private val previous = File(directory, "piket-diagnostics.previous.jsonl")
    private val export = File(directory, "piket-diagnostics.txt")

    @Synchronized fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching {
            rotateIfNeeded()
            val row = JSONObject().put("time", System.currentTimeMillis()).put("event", name)
            fields.forEach { (key, value) -> row.put(key, value ?: JSONObject.NULL) }
            current.appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }

    @Synchronized fun exportFile(): File = runCatching {
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

    @Synchronized fun clear() {
        listOf(current, previous, export).forEach { runCatching { it.delete() } }
        event("log_cleared")
    }

    private fun rotateIfNeeded() {
        if (!current.exists() || current.length() < MAX_BYTES) return
        previous.delete()
        current.renameTo(previous)
    }

    companion object { private const val MAX_BYTES = 1_500_000L }
}
