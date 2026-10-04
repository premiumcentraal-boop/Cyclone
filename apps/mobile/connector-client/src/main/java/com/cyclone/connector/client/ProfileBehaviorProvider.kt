package com.cyclone.connector.client

import android.content.Context
import android.os.Binder
import com.cyclone.connector.IProfileBehaviorProvider
import com.cyclone.connector.IProfileBehaviorResult
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Runs on a Binder worker; answer promptly (Cyclone's deadline is 250 ms). */
abstract class ProfileBehaviorProvider(private val context: Context) : IProfileBehaviorProvider.Stub() {
    abstract fun beforeLaunch(event: JSONObject): JSONObject?

    final override fun beforeLaunch(event: String?, result: IProfileBehaviorResult?) {
        val uid = Binder.getCallingUid()
        if (context.packageManager.getPackagesForUid(uid)?.toList() != listOf(CycloneConnector.CYCLONE_PACKAGE)) return
        val answer = runCatching {
            val body = JSONObject(event ?: "")
            require(body.opt("version") == 1 && body.optString("contract") == "cyclone.profile-startup/1")
            require(android.os.SystemClock.elapsedRealtime() < body.getLong("deadlineElapsedRealtimeMs"))
            beforeLaunch(body) ?: JSONObject().put("version", 1).put("configRef", JSONObject.NULL).put("state", "ready")
        }.getOrElse { JSONObject().put("version", 1).put("state", "failed").put("configRef", JSONObject.NULL) }
        runCatching { result?.complete(answer.toString()) }
    }

    companion object {
        /** Connector-owned private storage. Stable across updates; removed by uninstall/clear data. */
        fun stateDirectory(context: Context, profileId: String, androidUserId: Int, packageName: String): File {
            require(Regex("^Cyclone_[a-f0-9]{16}$").matches(profileId) && androidUserId >= 0)
            require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName))
            val tuple = "$profileId\n$androidUserId\n$packageName"
            val key = MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return File(context.noBackupFilesDir, "cyclone-profile-state-v1/$key").also { check(it.isDirectory || it.mkdirs()) }
        }
    }
}
