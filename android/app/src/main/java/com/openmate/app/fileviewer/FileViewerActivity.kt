package com.openmate.app.fileviewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.openmate.core.ui.theme.OpenMateTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FileViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return finish()

        setContent {
            OpenMateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileViewerScreen(
                        filePath = filePath,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"

        fun intent(
            context: Context,
            filePath: String,
        ) = Intent(context, FileViewerActivity::class.java).apply {
            putExtra(EXTRA_FILE_PATH, filePath)
        }
    }
}
