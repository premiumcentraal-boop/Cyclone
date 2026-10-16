package com.cyclone.mobile.runtime.safe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class SafeFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun atomicReplacementIsReadableAndLeavesNoPartialFile() {
        val file = temp.newFile("state")
        SafeFiles.atomic(file, byteArrayOf(1))
        SafeFiles.atomic(file, byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(2, 3), SafeFiles.readSettled(file))
        assertFalse(File(temp.root, "state.part").exists())
    }
    @Test fun emptyRegularFilesRemainReadableButMissingSourcesFail() {
        assertArrayEquals(byteArrayOf(), SafeFiles.readSettled(temp.newFile("empty")))
        assertThrows(IllegalStateException::class.java) { SafeFiles.readSettled(File(temp.root, "missing")) }
    }
    @Test fun oversizedSourceIsRejectedBeforeAllocatingItsBody() {
        val file = temp.newFile("large")
        RandomAccessFile(file, "rw").use { it.setLength(SafeSnapshot.MAX_ENTRY + 1) }
        assertThrows(IllegalStateException::class.java) { SafeFiles.readSettled(file) }
    }
}
