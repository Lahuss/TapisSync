package fr.tapissync

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal en mémoire, consultable dans l'écran Diagnostic et copiable. */
object AppLog {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE)

    @Synchronized
    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        try {
            Log.i("TapisSync", "[$tag] $msg")
        } catch (_: Throwable) {
            // Tests JVM : pas de logcat.
        }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
