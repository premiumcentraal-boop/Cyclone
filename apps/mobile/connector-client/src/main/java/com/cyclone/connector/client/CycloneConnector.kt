package com.cyclone.connector.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import com.cyclone.connector.ICycloneConnector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A connection to Cyclone on this phone (contract `cyclone.connector/1`, see SPEC.md).
 *
 * ```
 * val cyclone = CycloneConnector.connect(context)       // off the main thread
 * val hello = cyclone.hello()                            // approved? which scopes?
 * val profiles = cyclone.profiles()
 * cyclone.close()
 * ```
 *
 * Every call throws [CycloneConnectorException] with Cyclone's error code when it is refused (for example
 * `NOT_APPROVED` until the owner approves your app in Cyclone → Settings → Connectors).
 */
class CycloneConnector private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val service: ICycloneConnector,
) : AutoCloseable {

    companion object {
        const val CONTRACT = "cyclone.connector/1"
        const val CYCLONE_PACKAGE = "com.cyclone.mobile"
        const val ACTION_SERVICE = "com.cyclone.connector.SERVICE"
        /** The broadcast your wake receiver gets when new events wait. It carries nothing: call [events]. */
        const val ACTION_WAKE = "com.cyclone.connector.WAKE"
        /** The extra your entry activity gets: the id of the entry the owner tapped. */
        const val EXTRA_ENTRY_ID = "com.cyclone.connector.ENTRY_ID"

        /** True when Cyclone is installed on this phone (and visible to your app). */
        fun isCycloneInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(CYCLONE_PACKAGE, 0); true }.getOrDefault(false)

        /** Binds Cyclone and waits up to [timeoutMs]. Call it off the main thread; binding answers on the main thread. */
        fun connect(context: Context, timeoutMs: Long = 5_000): CycloneConnector {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Call CycloneConnector.connect off the main thread." }
            val app = context.applicationContext
            val latch = CountDownLatch(1)
            var bound: ICycloneConnector? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    bound = binder?.let(ICycloneConnector.Stub::asInterface)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    bound = null
                }
            }
            val intent = Intent(ACTION_SERVICE).setPackage(CYCLONE_PACKAGE)
            if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                runCatching { app.unbindService(connection) }
                throw CycloneConnectorException("CYCLONE_NOT_FOUND", "Cyclone isn't installed, or is older than 5.0.0-alpha.104.")
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || bound == null) {
                runCatching { app.unbindService(connection) }
                throw CycloneConnectorException("CYCLONE_NOT_ANSWERING", "Cyclone didn't answer in time.")
            }
            return CycloneConnector(app, connection, bound!!)
        }
    }

    /** One call. Returns the `result` object, or throws with Cyclone's error code. */
    fun call(method: String, args: JSONObject = JSONObject()): JSONObject {
        val answer = JSONObject(service.call(JSONObject().put("method", method).put("args", args).toString()))
        if (!answer.optBoolean("ok")) {
            val error = answer.optJSONObject("error") ?: JSONObject()
            throw CycloneConnectorException(error.optString("code", "UNKNOWN"), error.optString("message"))
        }
        return answer.optJSONObject("result") ?: JSONObject()
    }

    fun hello(): JSONObject = call("hello", JSONObject().put("contract", CONTRACT))

    fun profiles(): JSONObject = call("profiles")

    /** Saves your own data on a Cyclone profile (or clears it with null). At most 4 KB, never a secret. */
    fun setExt(profileId: String, value: JSONObject?): JSONObject =
        call("ext.set", JSONObject().put("profileId", profileId).put("value", value ?: JSONObject.NULL))

    fun setEntries(entries: List<Entry>): JSONObject = call("entries.set", JSONObject().put("entries", JSONArray(entries.map { it.toJson() })))

    fun entries(): List<Entry> {
        val array = call("entries.get").optJSONArray("entries") ?: JSONArray()
        return (0 until array.length()).map { Entry.fromJson(array.getJSONObject(it)) }
    }

    /** Events after [since]. Keep `next` and send it next time; on `reset`, read [profiles] again. */
    fun events(since: Long): JSONObject = call("events", JSONObject().put("since", since))

    /** The config API never invokes the launch provider. Null clears the blob. */
    fun getConfig(profileId: String, androidUserId: Int, packageName: String): JSONObject =
        call("config.get.v1", tuple(profileId, androidUserId, packageName))
    fun setConfig(profileId: String, androidUserId: Int, packageName: String, value: JSONObject?): JSONObject =
        call("config.set.v1", tuple(profileId, androidUserId, packageName).put("value", value ?: JSONObject.NULL))
    fun configStatus(profileId: String, androidUserId: Int, packageName: String, state: String): JSONObject =
        call("config.status.v1", tuple(profileId, androidUserId, packageName).put("state", state))
    fun startupStatus(profileId: String, androidUserId: Int, packageName: String): JSONObject =
        call("startup.status.v1", tuple(profileId, androidUserId, packageName))
    private fun tuple(profileId: String, androidUserId: Int, packageName: String) = JSONObject()
        .put("profileId", profileId).put("androidUserId", androidUserId).put("packageName", packageName)

    /** Keep the Binder alive and re-register after reconnecting. Null unregisters. */
    fun registerProfileProvider(provider: com.cyclone.connector.IProfileBehaviorProvider?): JSONObject {
        val answer = JSONObject(service.registerProfileProvider(JSONObject().put("version", 1).toString(), provider))
        if (!answer.optBoolean("ok")) {
            val error = answer.getJSONObject("error")
            throw CycloneConnectorException(error.getString("code"), error.getString("message"))
        }
        return answer.getJSONObject("result")
    }

    override fun close() {
        runCatching { context.unbindService(connection) }
    }

    /** An entry in the owner's profile list, shown as yours ("From <your label>"). */
    data class Entry(
        val id: String,
        val type: String,
        val label: String,
        val subtitle: String = "",
        /** A drawable resource name from your own app. */
        val icon: String? = null,
        val state: String = "ready",
        val statusText: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("label", label).put("subtitle", subtitle)
            .put("icon", icon ?: JSONObject.NULL).put("status", JSONObject().put("state", state).put("text", statusText))

        companion object {
            fun fromJson(o: JSONObject): Entry {
                val status = o.optJSONObject("status") ?: JSONObject()
                return Entry(o.getString("id"), o.getString("type"), o.getString("label"), o.optString("subtitle"),
                    if (o.isNull("icon")) null else o.optString("icon").takeIf { it.isNotEmpty() },
                    status.optString("state", "ready"), status.optString("text"))
            }
        }
    }
}

class CycloneConnectorException(val code: String, message: String) : Exception(message)
