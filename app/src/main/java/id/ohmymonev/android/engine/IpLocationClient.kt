package id.ohmymonev.android.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class IpCoordinates(val latitude: Double, val longitude: Double)

internal interface IpLocationSource {
    suspend fun locate(timeoutMs: Long): IpCoordinates
}

internal class IpLocationClient(
    private val openConnection: () -> HttpURLConnection = {
        URL(ENDPOINT).openConnection() as HttpURLConnection
    },
) : IpLocationSource {
    override suspend fun locate(timeoutMs: Long): IpCoordinates {
        val timeout = timeoutMs.coerceIn(1, MAX_TIMEOUT_MS)
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { continuation ->
                val activeConnection = AtomicReference<HttpURLConnection?>(null)
                val worker = Thread({
                    var connection: HttpURLConnection? = null
                    try {
                        connection = openConnection()
                        activeConnection.set(connection)
                        if (!continuation.isActive) {
                            connection.disconnect()
                            return@Thread
                        }
                        connection.connectTimeout = timeout.toInt()
                        connection.readTimeout = timeout.toInt()
                        connection.requestMethod = "GET"
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.setRequestProperty("Accept", "application/json")

                        val status = connection.responseCode
                        if (status == 429) throw LocationFailure("ip_http_429", 2, "Lokasi jaringan sedang dibatasi.")
                        if (status !in 200..299) throw LocationFailure("ip_http_$status", 2, "Lokasi jaringan tidak tersedia.")
                        if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                            throw LocationFailure("ip_response_too_large", 2, "Lokasi jaringan tidak tersedia.")
                        }
                        val body = connection.inputStream.use(::readBounded)
                        val json = JSONObject(String(body, Charsets.UTF_8))
                        val latitude = (json.opt("latitude") as? Number)?.toDouble()
                        val longitude = (json.opt("longitude") as? Number)?.toDouble()
                        if (latitude == null || longitude == null || !valid(latitude, longitude)) {
                            throw LocationFailure("ip_invalid_coordinates", 2, "Lokasi jaringan tidak tersedia.")
                        }
                        if (continuation.isActive) continuation.resume(IpCoordinates(latitude, longitude))
                    } catch (cancelled: CancellationException) {
                        if (continuation.isActive) continuation.resumeWithException(cancelled)
                    } catch (failure: LocationFailure) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (_: JSONException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            LocationFailure("ip_invalid_response", 2, "Lokasi jaringan tidak tersedia."),
                        )
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            LocationFailure("ip_network", 2, "Lokasi jaringan tidak tersedia."),
                        )
                    } finally {
                        activeConnection.getAndSet(null)?.disconnect()
                    }
                }, "monev-ip-location").apply { isDaemon = true }
                continuation.invokeOnCancellation {
                    Thread({
                        activeConnection.get()?.disconnect()
                        worker.interrupt()
                    }, "monev-ip-location-cancel").apply { isDaemon = true; start() }
                }
                worker.start()
            }
        } ?: throw LocationFailure("ip_timeout", 3, "Lokasi belum ditemukan. Coba lagi.")
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw LocationFailure("ip_response_too_large", 2, "Lokasi jaringan tidak tersedia.")
            }
            output.write(buffer, 0, count)
        }
    }

    companion object {
        const val ENDPOINT = "https://free.freeipapi.com/api/v1/json"
        const val MAX_TIMEOUT_MS = 8_000L
        const val MAX_RESPONSE_BYTES = 16 * 1024

        fun valid(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
    }
}
