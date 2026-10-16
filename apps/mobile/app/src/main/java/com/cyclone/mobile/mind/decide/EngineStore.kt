package com.cyclone.mobile.mind.decide

import android.content.Context
import java.io.File

/**
 * Plan 63: the engine log on this phone (`decide/engine.jsonl`, the last [EngineLog.MAX_KEPT] decisions). Counts,
 * times and chosen rungs only; never the request. Written off the routing thread (the race settles on its own pool).
 */
object EngineStore {
    private const val FILE = "engine.jsonl"
    private val lock = Any()

    fun save(context: Context, record: EngineRecord) = synchronized(lock) {
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            val kept = EngineLog.decode(target.takeIf { it.isFile }?.readText()) + record
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(EngineLog.encode(kept))
            temp.renameTo(target)
        }
    }

    fun records(context: Context): List<EngineRecord> = synchronized(lock) {
        EngineLog.decode(file(context).takeIf { it.isFile }?.readText())
    }

    fun forget(context: Context) = synchronized(lock) { runCatching { file(context).delete() } }

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "decide"), FILE)
}
