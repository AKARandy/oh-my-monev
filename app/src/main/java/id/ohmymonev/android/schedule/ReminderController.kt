package id.ohmymonev.android.schedule

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.engine.SiteStatusProbe
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.schedule.ScheduleCalculator
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One foreground owner for durable checks and the currently sounding alarm. */
internal class ReminderController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications = ReminderNotifications(context)
    private val preferences = store(context)
    private val sessions = SessionRepository.get(context)
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var enabled = false
    private var listening = false
    private var online = false
    private var check: Job? = null
    private var rerun = false
    private var timer: Job? = null
    private var playing: String? = null
    private var ringtone: Ringtone? = null
    private var ringJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val failures = mutableMapOf<String, Int>()
    private val warned = mutableSetOf<String>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            scope.launch { connectivityChanged() }
        }
        override fun onLost(network: Network) {
            scope.launch { connectivityChanged() }
        }
    }

    init {
        scope.launch {
            TimeSettingsStore(context).settings.map { it.masterEnabled && it.reminderEnabled }
                .distinctUntilChanged().collect { active ->
                    enabled = active
                    if (active) {
                        network.registerDefaultNetworkCallback(callback)
                        listening = true
                        connectivityChanged()
                    } else {
                        stopListening()
                        check?.cancel()
                        timer?.cancel()
                        stopAlarm()
                        clear(context)
                        failures.clear()
                        warned.clear()
                    }
                }
        }
        scope.launch {
            sessions.state.map { Triple(it.initializing, it.usable, it.version) }
                .distinctUntilChanged().collect { (initializing, _, _) ->
                    if (!initializing) {
                        if (!FeatureReadiness.read(context).ready) {
                            stopAlarm()
                            clear(context)
                        }
                        requestCheck()
                    }
                }
        }
    }

    fun handle(intent: Intent?) {
        val key = intent?.data?.schemeSpecificPart
        if (key != null && playing == key) {
            when (intent.action) {
                "stop" -> stopAlarm()
                "snooze" -> {
                    stopAlarm()
                    save(key, System.currentTimeMillis() + 600_000)
                    warned.removeAll { it.startsWith("$key:") }
                }
            }
        }
        requestCheck()
    }

    private fun connectivityChanged() {
        val connected = network.getNetworkCapabilities(network.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (connected && !online) {
            // Clear server backoff on reconnect, but preserve explicit snoozes.
            failures.keys.forEach { if (due(it) >= 0) save(it, 0) }
            failures.clear()
        }
        online = connected
        requestCheck()
    }

    private fun requestCheck() {
        if (!enabled || sessions.state.value.initializing) return
        if (check?.isActive == true) {
            rerun = true
            return
        }
        timer?.cancel()
        check = scope.launch(start = CoroutineStart.LAZY) {
            do {
                timer?.cancel()
                rerun = false
                runChecks()
            } while (rerun)
        }.also { it.start() }
    }

    private suspend fun runChecks() {
        val keys = preferences.all.keys.sorted()
        val now = System.currentTimeMillis()
        for (key in keys) {
            if (!enabled || !FeatureReadiness.read(context).ready) {
                stopAlarm()
                clear(context)
                return
            }
            val due = due(key)
            if (due < 0 || due > now) continue
            val date = LocalDate.parse(key.substringAfterLast('/'))
            val account = key.substringBeforeLast('/').takeUnless { it == UNBOUND }
            if (!online) {
                warn(key, "offline", "Tidak ada koneksi internet. Aplikasi akan memeriksa lagi saat internet tersedia.")
                continue
            }
            if (!sessions.state.value.hasToken) {
                warn(key, "no_session", "Sesi berakhir. Login kembali.")
                continue
            }
            val result = try {
                withTimeoutOrNull(45_000) { SiteStatusProbe(context).checkReminder(date, account) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!enabled || !FeatureReadiness.read(context).ready || this.due(key) != due ||
                !TimeSettingsStore(context).settingsOnce().let { it.masterEnabled && it.reminderEnabled }
            ) continue
            if (result?.credentialVersion != null && result.credentialVersion != sessions.state.value.version) {
                warn(key, "session_changed", "Sesi berubah. Aplikasi akan memeriksa lagi.")
                rerun = true
                continue
            }
            when {
                account != null && result?.account != null && result.account != account -> {
                    Log.i(TAG, "Check failed date=$date reason=account_changed")
                    preferences.edit().remove(key).apply()
                    notifications.cancel(key)
                }
                result?.sessionActive != true || result.account == null ||
                    result.credentialVersion == null || result.today == TodayState.UNKNOWN -> {
                    warn(key, "status_unverified", result?.error ?: "Situs tidak merespons. Aplikasi akan memeriksa lagi.")
                    if (sessions.state.value.hasToken) {
                        val attempt = failures.getOrDefault(key, 0)
                        val delay = listOf(1, 5, 15)[attempt.coerceAtMost(2)] * 60_000L
                        failures[key] = (attempt + 1).coerceAtMost(2)
                        save(key, System.currentTimeMillis() + delay)
                    }
                }
                else -> {
                    val resolvedKey = "${result.account}/$date"
                    val alreadyHandled = resolvedKey != key && preferences.contains(resolvedKey)
                    preferences.edit().putLong(key, DONE).apply {
                        if (!alreadyHandled) putLong(resolvedKey, DONE)
                    }.apply()
                    failures.remove(key)
                    warned.removeAll { it.startsWith("$key:") }
                    if (resolvedKey != key) notifications.cancel(key)
                    if (!alreadyHandled) deliver(resolvedKey, date, result.today)
                }
            }
        }
        val next = preferences.all.values.filterIsInstance<Long>().filter { it > now }.minOrNull()
        if (next != null) timer = scope.launch {
            delay((next - System.currentTimeMillis()).coerceAtLeast(1))
            requestCheck()
        }
    }

    private fun warn(key: String, reason: String, detail: String) {
        val marker = "$key:$detail"
        if (warned.add(marker)) {
            Log.i(TAG, "Check failed date=${key.substringAfterLast('/')} reason=$reason")
            notifications.post(key, "Status laporan belum dapat diperiksa.", detail)
        }
    }

    internal fun deliver(
        key: String,
        date: LocalDate,
        state: TodayState,
        now: ZonedDateTime = ZonedDateTime.now(ScheduleCalculator.asiaJakarta),
    ) {
        Log.i(TAG, "Check complete date=$date state=$state")
        val today = date == now.toLocalDate()
        val day = if (today) "hari ini" else date.format(
            java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("id")))
        when (state) {
            TodayState.FILLED -> notifications.post(key, "Laporan $day sudah terkirim.",
                "Tidak perlu mengisi lagi. Buka laporan untuk memeriksa isinya.", normal = today)
            TodayState.LIBUR, TodayState.LEAVE ->
                notifications.post(key, "Tidak perlu mengisi laporan $day.", "Periksa status di situs.")
            TodayState.UNFILLED -> {
                if (today && now.toLocalTime() < LocalTime.of(23, 59)) {
                    startAlarm(key, now)
                } else {
                    Log.i(TAG, "Alarm suppressed date=$date reason=deadline_passed")
                    notifications.post(key, "Laporan $day belum terkirim.", "Periksa laporan di situs.")
                }
            }
            TodayState.UNKNOWN -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun startAlarm(key: String, now: ZonedDateTime) {
        stopAlarm()
        val channel = notifications.alarmChannel()
        if (!notifications.post(key, "Laporan hari ini belum terkirim.",
                "Buka situs dan kirim laporan sebelum 23.59 WIB.", alarm = true)) {
            Log.i(TAG, "Alarm suppressed date=${now.toLocalDate()} reason=notification_blocked")
            return
        }
        playing = key
        val duration = java.time.Duration.between(now, now.withHour(23).withMinute(59).withSecond(0).withNano(0))
            .toMillis().coerceIn(1, 300_000)
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oh-my-monev:reminder")
            .apply { acquire(duration) }
        ringJob = scope.launch {
            try {
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                val end = android.os.SystemClock.elapsedRealtime() + duration
                var firstCycle = true
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (!firstCycle && !FeatureReadiness.read(context).ready) break
                    val policy = manager.currentInterruptionFilter
                    val allowed = policy == NotificationManager.INTERRUPTION_FILTER_ALL ||
                        policy == NotificationManager.INTERRUPTION_FILTER_ALARMS
                    val sound = allowed && channel.sound != null &&
                        audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM) > 0
                    val vibrate = allowed && channel.shouldVibrate() && vibrator.hasVibrator()
                    if (sound) {
                        try {
                            ringtone = RingtoneManager.getRingtone(context, channel.sound)?.also {
                                it.audioAttributes = channel.audioAttributes
                                it.play()
                            }
                        } catch (_: RuntimeException) {
                            Log.w(TAG, "Alarm sound failed date=${now.toLocalDate()}")
                        }
                    }
                    var vibrationRequested = false
                    if (vibrate) {
                        try {
                            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1),
                                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                            vibrationRequested = true
                        } catch (_: RuntimeException) {
                            Log.w(TAG, "Alarm vibration failed date=${now.toLocalDate()}")
                        }
                    }
                    if (firstCycle) {
                        val date = now.toLocalDate()
                        when {
                            ringtone != null || vibrationRequested ->
                                Log.i(TAG, "Alarm started date=$date sound=${ringtone != null} vibration=$vibrationRequested")
                            !allowed -> Log.i(TAG, "Alarm suppressed date=$date reason=interruption_filter")
                            else -> Log.i(TAG, "Alarm suppressed date=$date reason=no_enabled_output")
                        }
                        firstCycle = false
                    }
                    delay((end - android.os.SystemClock.elapsedRealtime()).coerceIn(1, 4_000))
                    ringtone?.stop()
                    ringtone = null
                    vibrator.cancel()
                }
            } finally {
                if (playing == key) {
                    ringJob = null
                    stopAlarm()
                }
            }
        }
    }

    private fun stopAlarm() {
        val key = playing ?: return
        playing = null
        ringJob?.cancel()
        ringJob = null
        ringtone?.stop()
        ringtone = null
        vibrator.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        notifications.cancel(key)
    }

    private fun stopListening() {
        if (listening) network.unregisterNetworkCallback(callback)
        listening = false
        online = false
    }

    fun close() {
        stopListening()
        stopAlarm()
        scope.cancel()
    }

    private fun due(key: String) = preferences.getLong(key, DONE)
    private fun save(key: String, due: Long) = preferences.edit().putLong(key, due).apply()

    companion object {
        private const val DONE = -1L
        private const val UNBOUND = "@unbound"
        private const val TAG = "MonevReminder"
        private fun store(context: Context) = context.getSharedPreferences("reminder_checks", Context.MODE_PRIVATE)

        fun enqueue(context: Context, account: String?, date: LocalDate) {
            val store = store(context)
            val key = "${account ?: UNBOUND}/$date"
            if (store.contains(key) || store.getLong("$UNBOUND/$date", DONE) >= 0) return
            if (store.edit().putLong(key, 0).commit()) {
                val reason = if (account == null) "resolve_account" else "scheduled"
                Log.i(TAG, "Check queued date=$date reason=$reason")
            } else {
                Log.w(TAG, "Check failed date=$date reason=queue_write")
            }
        }

        fun hasPending(context: Context) = store(context).all.values.any { it is Long && it >= 0 }

        fun clear(context: Context) {
            val store = store(context)
            store.all.keys.forEach { ReminderNotifications(context).cancel(it) }
            store.edit().clear().commit()
        }

        fun stopFromActivity(context: Context, key: String) {
            context.startService(Intent(context, GuardService::class.java).setAction("stop")
                .setData(android.net.Uri.fromParts("monev-reminder", key, null)))
        }
    }
}
