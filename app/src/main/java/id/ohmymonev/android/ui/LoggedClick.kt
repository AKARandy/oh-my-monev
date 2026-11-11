package id.ohmymonev.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import id.ohmymonev.android.data.ActionLog

@Composable
internal fun loggedClick(actionName: String, action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    return {
        ActionLog.record(context, "ui.click", actionName)
        action()
    }
}
