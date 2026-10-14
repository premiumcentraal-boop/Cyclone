package com.cyclone.mobile.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plan 59 (build 1): where this phone keeps its accounts. Only the **Main** profile's Cyclone holds the book, so a
 * deleted profile can never take identities with it.
 *
 * The whole book is one AES-256-GCM box under an Android Keystore key that never leaves the phone. The key isn't bound
 * to the screen lock, so a task can fill a login while the owner is away. Viewing one on the Accounts page asks for the
 * phone's own lock first (the page does that). Nothing here logs, and the prefs file is NEVER carried to another
 * profile or backed up (PortableSettings).
 */
object IdentityVault {
    const val PREFS = "cyclone_identities_v1"
    private const val BOOK = "book"
    private const val KEY_ALIAS = "cyclone.identities.aes.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AAD = "cyclone-identities/v1"
    private val lock = Any()

    /** Seams for JVM tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    fun isMain(context: Context): Boolean = IdentitySaver.currentProfile(context).first == IdentityBook.MAIN

    /** The book, with expired trash purged and identities of deleted profiles marked not bound. */
    fun book(context: Context, profiles: Set<String>? = null): IdentityBook = synchronized(lock) {
        check(isMain(context)) { "Accounts are kept in the Main profile." }
        val book = load(context)
        val now = clock()
        val changed = book.expire(now) + (profiles?.let { book.applyRoster(it, now) } ?: 0)
        if (changed > 0) store(context, book)
        book
    }

    fun <T> change(context: Context, block: (IdentityBook, Long) -> T): T = synchronized(lock) {
        check(isMain(context)) { "Accounts are kept in the Main profile." }
        val book = load(context)
        val out = block(book, clock())
        store(context, book)
        out
    }

    /** The book, stamped with this phone's hive id and name so every change here says who made it. */
    private fun load(context: Context): IdentityBook = loadBox(context).also { book ->
        book.me = runCatching { HiveKeyring.me(context) }.getOrDefault("")
        book.myName = android.os.Build.MODEL.orEmpty().take(60)
    }

    private fun loadBox(context: Context): IdentityBook {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BOOK, null) ?: return IdentityBook()
        return runCatching {
            val box = JSONObject(raw)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(box.getString("iv"), Base64.NO_WRAP)))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(Base64.decode(box.getString("ct"), Base64.NO_WRAP))
            try { IdentityBook.fromJson(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
        }.getOrElse { throw IllegalStateException("The saved accounts couldn't be opened on this phone.") }
    }

    private fun store(context: Context, book: IdentityBook) {
        val plain = book.toJson().toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val ct = cipher.doFinal(plain)
            val box = JSONObject().put("v", 1).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(BOOK, box.toString()).commit()) {
                "The accounts couldn't be saved."
            }
        } finally { plain.fill(0) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}
