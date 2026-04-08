package com.example.seniordesignmobileapp.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.seniordesignmobileapp.model.SavedSessionSummary
import java.io.File

fun shareSavedSession(
    context: Context,
    session: SavedSessionSummary,
) {
    val file = File(session.absolutePath)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, session.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(shareIntent, "Export recording").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
