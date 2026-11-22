package com.rackjabbit.adskip.debug

import android.content.Context
import java.io.File

object DebugFiles {
    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "debug").apply { mkdirs() }
    }

    fun latest(context: Context): File? = File(dir(context), "latest.txt").takeIf { it.exists() && it.length() > 0L }
}

class DumpWriter(private val dir: File) {
    fun write(contents: String): File {
        dir.mkdirs()
        val stamp = java.time.Instant.now().toString().replace(":", "")
        val file = File(dir, "node-tree-$stamp.txt")
        file.writeText(contents)
        File(dir, "latest.txt").writeText(contents)
        dir.listFiles { candidate -> candidate.name.startsWith("node-tree-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(8)
            ?.forEach { it.delete() }
        return file
    }
}
