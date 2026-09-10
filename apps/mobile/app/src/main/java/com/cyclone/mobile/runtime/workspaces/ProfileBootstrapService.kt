package com.cyclone.mobile.runtime.workspaces

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cyclone.mobile.ai.OpenRouterSecretStore
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/** Non-exported, short-lived receiver started by the trusted root profile manager. */
class ProfileBootstrapService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val folder = createDeviceProtectedStorageContext().filesDir
        val input = File(folder, "profile-bootstrap.json")
        val result = File(folder, "profile-bootstrap-result.json")
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
                }.generateKeyPair()
            }
            if (input.exists()) {
                val transfer = JSONObject(input.readText())
                ProfileBootstrapContract.validateTransfer(transfer.getInt("source"), transfer.getInt("target"), ProfileSetupRuntime.currentUserId())
                val ai = transfer.getJSONObject("ai")
                val edit = getSharedPreferences("cyclone_ai", MODE_PRIVATE).edit()
                ProfileBootstrapContract.aiKeys.forEach { key ->
                    if (ai.has(key)) when (val value = ai.get(key)) {
                        is Boolean -> edit.putBoolean(key, value)
                        is String -> edit.putString(key, value)
                    }
                }
                check(edit.commit())
                check(getSharedPreferences("cyclone_profile_registry", MODE_PRIVATE).edit()
                    .putString("profiles", transfer.getString("profiles")).commit())
                check(createDeviceProtectedStorageContext().getSharedPreferences("cyclone_profile_origin", MODE_PRIVATE).edit()
                    .putInt("source", transfer.getInt("source")).putString("profile", transfer.getString("profile")).commit())
                if (transfer.has("key")) {
                    val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                    cipher.init(Cipher.DECRYPT_MODE, store.getKey(ALIAS, null), OAEP)
                    val bytes = cipher.doFinal(Base64.decode(transfer.getString("key"), Base64.NO_WRAP))
                    try { OpenRouterSecretStore.save(this, String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
                    check(OpenRouterSecretStore.hasKey(this))
                }
                val permissions = transfer.getJSONArray("permissions")
                for (index in 0 until permissions.length()) {
                    val permission = permissions.getString(index)
                    check(permission in ProfileBootstrapContract.permissions || permission == "moe.shizuku.manager.permission.API_V23")
                    check(checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }
                if (transfer.optBoolean("overlay")) check(android.provider.Settings.canDrawOverlays(this))
                if (transfer.optBoolean("accessibility")) check(android.provider.Settings.Secure.getString(contentResolver,
                    "enabled_accessibility_services").orEmpty().split(':').contains(ProfileBootstrapContract.ACCESSIBILITY))
                if (transfer.optBoolean("listener")) {
                    val components = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().split(':')
                    check(components.any { it == ProfileBootstrapContract.LISTENER || it == "com.cyclone.mobile/com.cyclone.mobile.CycloneNotificationListener" })
                }
                result.writeText(JSONObject().put("nonce", transfer.getString("nonce")).put("user", ProfileSetupRuntime.currentUserId()).put("ok", true).toString())
            }
            input.delete()
            File(folder, "profile-bootstrap-public.txt").writeText(Base64.encodeToString(store.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP))
        } catch (_: Exception) {
            input.delete()
            // Never log preference contents, encrypted payloads, or credential failures with values.
            result.writeText("{\"ok\":false}")
        } finally {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    companion object {
        private const val ALIAS = "cyclone.profile.bootstrap.v1"
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
