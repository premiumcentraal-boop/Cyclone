package com.cyclone.mobile.runtime.safe

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors
import android.os.FileObserver
import java.io.File
import java.util.Calendar

/** OS-owned jobs survive process death. No service loop or extra permissions. */
internal object SafeSchedule {
    private const val BACKGROUND = 61350
    private const val NIGHTLY = 61351
    private var observer: FileObserver? = null
    private var lastChange = 0L
    private fun allowed(context: Context): Boolean = SafeRuntime(context).state().let {
        it.optBoolean("enabled") && !it.optBoolean("deleting") && !it.optBoolean("restorePending")
    }
    internal fun nightlyDelay(now: Long): Long = Calendar.getInstance().run {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        timeInMillis - now
    }
    fun install(context: Context) {
        if (!allowed(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, SafeBackupJob::class.java)
        if (android.os.Process.myUid() / 100000 == 0 && scheduler.getPendingJob(NIGHTLY) == null) scheduler.schedule(JobInfo.Builder(NIGHTLY, component)
            .setMinimumLatency(nightlyDelay(System.currentTimeMillis())).setRequiresCharging(true).setPersisted(true).build())
        if (scheduler.getPendingJob(BACKGROUND) == null) scheduler.schedule(JobInfo.Builder(BACKGROUND, component)
            .setMinimumLatency(10 * 60 * 1000L).setPersisted(true).build())
        watch(context.applicationContext)
    }
    fun background(context: Context) {
        if (!allowed(context)) return
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(BACKGROUND, ComponentName(context, SafeBackupJob::class.java))
            .setMinimumLatency(1000).setPersisted(true).build())
    }
    fun timeChanged(context: Context) {
        if (!allowed(context)) return
        context.getSystemService(JobScheduler::class.java).cancel(NIGHTLY)
        install(context)
    }
    @Synchronized private fun watch(context: Context) {
        if (observer != null) return
        val roots = buildList {
            add(context.filesDir)
            add(File(context.dataDir, "shared_prefs"))
            add(File(context.dataDir, "databases"))
            add(File(context.createDeviceProtectedStorageContext().dataDir, "shared_prefs"))
        }
        val directories = roots.flatMap { root -> root.walkTopDown().onEnter {
            !java.nio.file.Files.isSymbolicLink(it.toPath()) && (root != context.filesDir || it == root ||
                SafeManifest.file(it.relativeTo(root).invariantSeparatorsPath)?.kind != SafeManifest.Kind.NEVER)
        }
            .filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.toList() }.distinct()
        observer = object : FileObserver(directories, CLOSE_WRITE or MOVED_TO or DELETE or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                synchronized(SafeSchedule) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastChange < 1000) return
                    if (!allowed(context)) return
                    val prefsName = path?.substringBefore(".xml")
                    if (SafeManifest.stores.any { it.location in setOf(SafeManifest.Location.PREFS, SafeManifest.Location.DEVICE_PREFS) &&
                        it.name == prefsName && it.kind == SafeManifest.Kind.NEVER }) return
                    lastChange = now
                    context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(BACKGROUND, ComponentName(context, SafeBackupJob::class.java))
                        .setMinimumLatency(10 * 60 * 1000L).setPersisted(true).build())
                    if (event and 0x40000000 != 0) { // Linux IN_ISDIR; Android exposes only the event bits.
                        observer?.stopWatching(); observer = null; watch(context)
                    }
                }
            }
        }.also { it.startWatching() }
    }
    fun completed(context: Context, params: JobParameters) {
        if (params.jobId == NIGHTLY) context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(NIGHTLY, ComponentName(context, SafeBackupJob::class.java))
                .setMinimumLatency(nightlyDelay(System.currentTimeMillis())).setRequiresCharging(true).setPersisted(true).build())
    }
    @Synchronized fun stop(context: Context) {
        observer?.stopWatching(); observer = null
        context.getSystemService(JobScheduler::class.java).cancel(BACKGROUND)
        context.getSystemService(JobScheduler::class.java).cancel(NIGHTLY)
    }
}

class SafeClockReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action in setOf(android.content.Intent.ACTION_TIME_CHANGED, android.content.Intent.ACTION_TIMEZONE_CHANGED))
            runCatching { SafeSchedule.timeChanged(context.applicationContext) }
    }
}

class SafeBackupJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            val failed = runCatching {
                val user = android.os.Process.myUid() / 100000
                if (user == 0) SafeRuntime(applicationContext).backup() else SafeRootStore().notifyMain(user)
            }.isFailure
            jobFinished(params, failed)
            if (!failed) SafeSchedule.completed(applicationContext, params)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
