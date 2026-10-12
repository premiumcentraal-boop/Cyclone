package com.cyclone.mobile.connector

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.cyclone.connector.IProfileBehaviorProvider
import com.cyclone.connector.IProfileBehaviorResult
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Ephemeral providers. No persisted Binder, component launch, URI dereference or behavior execution. */
object ProfileBehaviorRuntime {
    private data class Provider(val uid: Int, val connectorId: String, val binder: IProfileBehaviorProvider, val death: IBinder.DeathRecipient)
    private val providers = ConcurrentHashMap<Int, Provider>()
    private val dispatches = ConcurrentHashMap<Pair<Int, ProfileConfigKey>, Any>()
    private val replies = ConcurrentHashMap<Pair<Int, ProfileConfigKey>, JSONObject>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(16),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "cyclone-profile-provider").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val launches = ProfileLaunchTracker()

    fun register(context: Context, uid: Int, request: String?, binder: IProfileBehaviorProvider?): String {
        if (request == null || request.toByteArray(Charsets.UTF_8).size > ConnectorContract.REQUEST_MAX_BYTES) return error("BAD_REQUEST", "Invalid registration request size.")
        val args = runCatching { ProfileConfigRules.checkNesting(request); JSONObject(request) }.getOrNull()
            ?: return error("BAD_REQUEST", "Send a JSON object with version 1.")
        val answer = ConnectorRuntime.call(context, uid, JSONObject().put("method", "startup.check.v1").put("args", args).toString())
        if (!JSONObject(answer).optBoolean("ok")) return answer
        val caller = ConnectorDiscovery.caller(context, uid) ?: return error("NOT_A_CONNECTOR", "No connector identity.")
        // Registrations are local to the Cyclone installation's Android user. Never reuse an owner-user provider.
        if (uid / 100_000 != android.os.Process.myUid() / 100_000) return error("BAD_REQUEST", "Register with Cyclone in your own Android user.")
        synchronized(providers) {
            remove(uid)
            if (binder != null) {
                val death = IBinder.DeathRecipient { synchronized(providers) { if (providers[uid]?.binder?.asBinder() == binder.asBinder()) remove(uid) } }
                try { binder.asBinder().linkToDeath(death, 0) }
                catch (_: android.os.RemoteException) { return error("BAD_REQUEST", "Provider is already disconnected.") }
                providers[uid] = Provider(uid, caller.manifest!!.id, binder, death)
                if (!binder.asBinder().isBinderAlive) remove(uid)
            }
        }
        return JSONObject().put("ok", true).put("result", JSONObject().put("version", 1).put("registered", binder != null)).toString()
    }

    private fun error(code: String, message: String) = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun remove(uid: Int) {
        providers.remove(uid)?.let { runCatching { it.binder.asBinder().unlinkToDeath(it.death, 0) } }
        replies.keys.removeAll { it.first == uid }
        dispatches.keys.removeAll { it.first == uid }
    }
    fun revoke(connectorId: String) = synchronized(providers) {
        providers.values.filter { it.connectorId == connectorId }.forEach { remove(it.uid) }
    }
    fun switched(user: Int) = launches.switched(user)
    fun status(uid: Int, key: ProfileConfigKey): JSONObject = replies[uid to key]?.let { JSONObject(it.toString()) }
        ?: JSONObject().put("version", 1).put("state", "unknown").put("configRef", JSONObject.NULL)

    fun beforeIntent(context: Context, intent: Intent) {
        runCatching {
            val pkg = intent.component?.packageName ?: context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: return
            beforeLaunch(context, pkg, android.os.Process.myUid() / 100_000)
        }
    }

    /** One total 250 ms budget for all providers, including dispatch. Failure never vetoes the launch. */
    fun beforeLaunch(context: Context, packageName: String, androidUserId: Int, eventType: String? = null) {
        runCatching {
            val profile = ProfileRegistryStore.records(context).singleOrNull {
                it.androidUserId == androidUserId && it.ready && !it.inTrash && packageName in it.packages
            } ?: return
            val key = ProfileConfigKey(profile.id, androidUserId, packageName)
            val type = eventType ?: launches.next(key)
            require(type in ProfileLaunchTracker.TYPES)
            val selected = providers.values.filter { it.uid / 100_000 == androidUserId }
            val deadline = android.os.SystemClock.elapsedRealtime() + 250
            val dispatch = Any()
            selected.forEach { p -> dispatches[p.uid to key] = dispatch; replies.remove(p.uid to key) }
            val latch = CountDownLatch(selected.size)
            val finished = AtomicBoolean(false)
            selected.forEach { p ->
                val pair = p.uid to key
                val once = AtomicBoolean(false)
                fun finish(value: JSONObject) {
                    if (once.compareAndSet(false, true)) {
                        synchronized(finished) { if (!finished.get() && dispatches[pair] === dispatch && providers[p.uid] === p) { if (replies.size >= 256) replies.clear(); replies[pair] = value } }
                        latch.countDown()
                    }
                }
                try {
                    workers.execute {
                        val caller = ConnectorDiscovery.caller(context, p.uid)
                        val granted = caller?.let { ConnectorIdentity.granted(it, ConnectorIdentity.approvalFor(it, ConnectorRuntime.approvals(context))) }.orEmpty()
                        if (ConnectorScope.PROFILE_STARTUP !in granted || providers[p.uid] !== p) {
                            finish(JSONObject().put("version", 1).put("state", "failed").put("configRef", JSONObject.NULL))
                            return@execute
                        }
                        if (finished.get() || android.os.SystemClock.elapsedRealtime() >= deadline) return@execute
                        val event = key.json().put("version", 1).put("contract", "cyclone.profile-startup/1").put("eventType", type).put("deadlineElapsedRealtimeMs", deadline)
                        val replyGate = ProfileReplyGate(p.uid, deadline, android.os.SystemClock::elapsedRealtime)
                        val reply = object : IProfileBehaviorResult.Stub() {
                            override fun complete(response: String?) {
                                if (!replyGate.accept(Binder.getCallingUid())) return
                                finish(runCatching { ProfileConfigRules.reply(response) }.getOrElse {
                                    JSONObject().put("version", 1).put("state", "failed").put("configRef", JSONObject.NULL)
                                })
                            }
                        }
                        runCatching { p.binder.beforeLaunch(event.toString(), reply) }.onFailure {
                            finish(JSONObject().put("version", 1).put("state", "failed").put("configRef", JSONObject.NULL))
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    finish(JSONObject().put("version", 1).put("state", "degraded").put("configRef", JSONObject.NULL))
                }
            }
            try { latch.await(maxOf(0, deadline - android.os.SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally {
                synchronized(finished) {
                    finished.set(true)
                    selected.forEach { p ->
                        val pair = p.uid to key
                        // A reply belongs only to this dispatch. Expired replies cannot change the result.
                        if (dispatches[pair] === dispatch && providers[p.uid] === p && !replies.containsKey(pair)) replies[pair] = JSONObject().put("version", 1)
                            .put("state", "degraded").put("configRef", JSONObject.NULL)
                        dispatches.remove(pair, dispatch)
                    }
                }
            }
        }
    }
}

