package com.rackjabbit.adskip.data

import android.content.Context
import com.rackjabbit.adskip.engine.ConfigStore
import java.io.File

object ConfigFiles {
    @Volatile private var store: ConfigStore? = null

    fun store(context: Context): ConfigStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: create(context.applicationContext).also { store = it }
        }
    }

    private fun create(context: Context): ConfigStore {
        val defaults = context.assets.open("default_ad_config.json").bufferedReader().use { it.readText() }
        return ConfigStore(File(context.filesDir, "ad_config.json"), defaults).also { it.ensure() }
    }
}
