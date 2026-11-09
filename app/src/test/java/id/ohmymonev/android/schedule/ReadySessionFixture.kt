package id.ohmymonev.android.schedule

import android.Manifest
import android.app.Application
import android.content.Context
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.SessionTokenStore
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

internal fun readySession(context: Context) = runBlocking {
    val app = context.applicationContext as Application
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    val vault = SessionTokenStore(context) { SecretKeySpec(ByteArray(16), "AES") }
    vault.save("fixture-token")
    val session = SessionRepository(context, vault)
    session.confirm(requireNotNull(session.credentials()))
    ReflectionHelpers.setStaticField(SessionRepository::class.java, "instance", session)
}
