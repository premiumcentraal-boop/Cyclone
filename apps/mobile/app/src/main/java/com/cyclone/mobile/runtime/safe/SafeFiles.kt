package com.cyclone.mobile.runtime.safe

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SafeFiles {
    fun readSettled(file: File): ByteArray {
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "SAFE_SOURCE_INVALID" }
        val size = file.length(); val modified = file.lastModified()
        check(size in 0..SafeSnapshot.MAX_ENTRY) { "SAFE_SOURCE_LIMIT" }
        val bytes = file.readBytes()
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != size ||
            file.lastModified() != modified || bytes.size.toLong() != size) {
            bytes.fill(0); error("SAFE_SOURCE_UNSETTLED")
        }
        return bytes
    }
    fun atomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        try {
            FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
    }
}
