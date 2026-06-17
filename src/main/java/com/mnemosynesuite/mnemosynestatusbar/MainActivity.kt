package com.mnemosynesuite.mnemosynestatusbar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                // Note: The user MUST manually toggle the switch in the settings screen
            }
        }

        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Turn on 'Mnemosyne Status Engine' to take over bar rendering", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Please enable Mnemosyne in Accessibility Settings manually", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}