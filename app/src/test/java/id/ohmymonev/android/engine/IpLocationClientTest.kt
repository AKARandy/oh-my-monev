package id.ohmymonev.android.engine

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IpLocationClientTest {
    @Test fun readsOnlySuccessfulNumericCoordinates() = runBlocking {
        val connection = FixtureConnection(
            200,
            """{"ipVersion":4,"ipAddress":"192.0.2.1","latitude":-6.2,"longitude":106.8}""",
        )

        val result = IpLocationClient { connection }.locate(9_000)
        waitForDisconnect(connection)

        assertEquals(-6.2, result.latitude, 0.0)
        assertEquals(106.8, result.longitude, 0.0)
        assertEquals("GET", connection.requestMethod)
        assertEquals("https://free.freeipapi.com/api/v1/json", IpLocationClient.ENDPOINT)
        assertFalse(connection.instanceFollowRedirects)
        assertNull(connection.getRequestProperty("Cookie"))
        assertTrue(connection.readTimeout in 1..8_000)
        assertTrue(connection.disconnected)
    }

    @Test fun rejectsRateLimitAndDoesNotReadTheErrorBody() = runBlocking {
        val connection = FixtureConnection(429, "{}")

        val failure = failure { IpLocationClient { connection }.locate(1_000) }
        waitForDisconnect(connection)

        assertEquals("ip_http_429", failure.reason)
        assertEquals("Lokasi jaringan sedang dibatasi.", failure.message)
        assertTrue(connection.disconnected)
        assertFalse(connection.inputOpened)
    }

    @Test fun rejectsStringCoordinatesAndOutOfRangeValues() = runBlocking {
        val stringCoordinates = FixtureConnection(
            200,
            """{"success":true,"latitude":"-6.2","longitude":106.8}""",
        )
        val outOfRange = FixtureConnection(
            200,
            """{"success":true,"latitude":91,"longitude":106.8}""",
        )

        assertEquals("ip_invalid_coordinates", failure { IpLocationClient { stringCoordinates }.locate(1_000) }.reason)
        assertEquals("ip_invalid_coordinates", failure { IpLocationClient { outOfRange }.locate(1_000) }.reason)
    }

    @Test fun rejectsDeniedMalformedAndOversizedResponses() = runBlocking {
        val denied = FixtureConnection(403, """{"message":"Private response text"}""")
        val malformed = FixtureConnection(200, "<html>Not JSON</html>")
        val oversized = FixtureConnection(
            200,
            " ".repeat(IpLocationClient.MAX_RESPONSE_BYTES + 1),
        )

        val deniedFailure = failure { IpLocationClient { denied }.locate(1_000) }
        val malformedFailure = failure { IpLocationClient { malformed }.locate(1_000) }
        val oversizedFailure = failure { IpLocationClient { oversized }.locate(1_000) }
        assertEquals("ip_http_403", deniedFailure.reason)
        assertFalse(denied.inputOpened)
        assertEquals("ip_invalid_response", malformedFailure.reason)
        assertEquals("ip_response_too_large", oversizedFailure.reason)
    }

    @Test fun timeoutDisconnectsThePendingConnection() = runBlocking {
        val connection = BlockingConnection()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                IpLocationClient { connection }.locate(100)
            } catch (_: LocationFailure) { }
        }

        assertTrue(connection.started.await(1, TimeUnit.SECONDS))
        job.join()
        assertTrue(connection.disconnectedLatch.await(1, TimeUnit.SECONDS))
    }

    private suspend fun failure(block: suspend () -> IpCoordinates): LocationFailure = try {
        block()
        error("Expected location failure")
    } catch (failure: LocationFailure) {
        failure
    }

    private suspend fun waitForDisconnect(connection: FixtureConnection) {
        repeat(100) {
            if (connection.disconnected) return
            delay(5)
        }
    }

    private open class FixtureConnection(status: Int, private val body: String) : HttpURLConnection(URL("https://fixture.test")) {
        private val statusCode = status
        @Volatile var disconnected = false
        var inputOpened = false

        override fun connect() = Unit
        override open fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = statusCode
        override fun getContentLengthLong() = body.toByteArray(Charsets.UTF_8).size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)).also { inputOpened = true }
    }

    private class BlockingConnection : FixtureConnection(200, "{}") {
        val started = CountDownLatch(1)
        val disconnectedLatch = CountDownLatch(1)

        override fun getResponseCode(): Int {
            started.countDown()
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { }
            return 200
        }

        override fun disconnect() {
            super.disconnect()
            disconnectedLatch.countDown()
        }
    }
}
