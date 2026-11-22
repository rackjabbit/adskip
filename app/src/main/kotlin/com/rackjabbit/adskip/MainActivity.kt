package com.rackjabbit.adskip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rackjabbit.adskip.data.ConfigFiles
import com.rackjabbit.adskip.data.SettingsRepository
import com.rackjabbit.adskip.ui.AdSkipTheme
import com.rackjabbit.adskip.ui.SettingsApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(applicationContext)
        ConfigFiles.store(applicationContext)
        setContent {
            AdSkipTheme {
                SettingsApp(repository)
            }
        }
    }
}
