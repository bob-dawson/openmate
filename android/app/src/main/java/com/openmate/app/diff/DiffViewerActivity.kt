package com.openmate.app.diff

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
class DiffViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return finish()
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return finish()
        val toolName = intent.getStringExtra(EXTRA_TOOL_NAME) ?: return finish()
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)

        setContent {
            OpenMateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiffViewerScreen(
                        sessionId = sessionId,
                        messageId = messageId,
                        toolName = toolName,
                        targetFilePath = filePath,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_TOOL_NAME = "tool_name"
        private const val EXTRA_FILE_PATH = "file_path"

        fun intent(
            context: Context,
            sessionId: String,
            messageId: String,
            toolName: String,
            filePath: String? = null,
        ) = Intent(context, DiffViewerActivity::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_TOOL_NAME, toolName)
            putExtra(EXTRA_FILE_PATH, filePath)
        }
    }
}
