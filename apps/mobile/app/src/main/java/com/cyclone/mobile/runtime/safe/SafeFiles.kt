package com.cyclone.mobile.runtime.safe

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SafeFiles {
    fun atomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        try {
            FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
    }
}
