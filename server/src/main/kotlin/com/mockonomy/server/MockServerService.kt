package com.mockonomy.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mockonomy.data.BodyStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.hydrate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The foreground service that keeps the servers alive.
 *
 * The product's core scenario is the user switching to the app under test; at that
 * moment Mockonomy goes to the background. If the server were tied to the Activity
 * lifecycle the socket would close and the product would be useless. That is why
 * [MockServer] is started **only** from here.
 *
 * The service type is `specialUse`: a local development server has no time limit.
 *
 * **Multiple servers:** the service carries several environments at once. A single
 * notification summarizes all of them and offers one "Stop all" — a notification per
 * server would raise the question of which one holds the foreground. Individual servers
 * are stopped from the app. When the last server stops, the service shuts itself down.
 */
@AndroidEntryPoint
class MockServerService : Service() {

    @Inject
    lateinit var controller: MockServerController

    @Inject
    lateinit var environmentStore: EnvironmentStore

    @Inject
    lateinit var bodyStore: BodyStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // The notification is updated as the state changes: "Starting…" → running servers.
        // When the last server stops (possibly one by one from the UI) there is no reason
        // for the service to stay alive.
        scope.launch {
            var hadServers = false
            controller.running.collect { servers ->
                if (servers.isNotEmpty()) {
                    hadServers = true
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(servers))
                } else if (hadServers) {
                    shutdown()
                }
            }
        }

        // Hot reload: when a running environment is written to disk, the server is updated
        // without restarting. Port changes included — `MockServer.update` handles those.
        scope.launch {
            environmentStore.saved.collect { id ->
                if (!controller.isRunning(id)) return@collect
                environmentStore.load(id)?.let { controller.update(bodyStore.hydrate(it)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val target = intent.getStringExtra(EXTRA_ENVIRONMENT_ID)
            if (target == null) {
                shutdown()
            } else {
                // If no servers remain, the `running` collector above shuts the service down.
                controller.stopAsync(target)
            }
            return START_NOT_STICKY
        }

        // After startForegroundService() the system expects startForeground() within 5
        // seconds; otherwise it kills the app with an ANR. That is why this is called
        // BEFORE loading the environment.
        startInForeground(buildNotification(controller.running.value))

        val environmentId = intent?.getStringExtra(EXTRA_ENVIRONMENT_ID)
        if (environmentId == null) {
            shutdown()
            return START_NOT_STICKY
        }

        scope.launch {
            val environment = environmentStore.load(environmentId)
            if (environment == null) {
                stopIfIdle()
                return@launch
            }

            // hydrate: large bodies moved to files are handed to the engine inline.
            controller.start(bodyStore.hydrate(environment), environment.port)

            // It failed (e.g. a port conflict) and there is no other server to carry, so the
            // service shuts down. `controller.stopAll()` is NOT called — that would also kill
            // the other servers that are still running.
            stopIfIdle()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The stop work runs in the controller's OWN scope: had we used `scope.launch`
        // here, the `scope.cancel()` right below would cancel it before it started and the
        // sockets would stay open. `runBlocking` does not work either — waiting for N
        // servers to shut down on the main thread is an ANR.
        controller.stopAllAsync()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * If the user removes the app from the recent tasks, the servers stop too.
     *
     * The alternative was keeping the server alive; but a socket that keeps listening in
     * the background after the user closed the app would be a surprise. "Stop all" in the
     * notification stays the clear, single way.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    /** Shuts the service down if no server is running; otherwise leaves it alone. */
    private fun stopIfIdle() {
        if (controller.running.value.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shutdown() {
        controller.stopAllAsync()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // The specialUse constant was added in API 34; on older versions no type is
            // passed and the manifest declaration is enough.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(servers: List<RunningServer>): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, MockServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mockonomy_notification)
            .setContentTitle(notificationTitle(servers))
            .setContentText(notificationText(servers))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(
                        if (servers.size > 1) R.string.mockonomy_notification_stop_all else R.string.mockonomy_notification_stop,
                    ),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    /** The environment name for a single server; the count for multiple servers. */
    private fun notificationTitle(servers: List<RunningServer>): String = when (servers.size) {
        0 -> getString(R.string.mockonomy_notification_starting)
        1 -> servers.first().environmentName
        else -> getString(R.string.mockonomy_notification_running_count, servers.size)
    }

    private fun notificationText(servers: List<RunningServer>): String? = when (servers.size) {
        0 -> null
        1 -> servers.first().let { server ->
            val base = "http://$LOOPBACK:${server.port}"
            // While the proxy is on, the user should see in the notification that the device
            // forwards traffic to a real server.
            if (server.proxyTarget != null) "$base · proxying → ${server.proxyTarget}" else base
        }
        // With multiple servers the address list must fit on one line: name + port is
        // enough; proxy targets are visible in the app.
        else -> servers.joinToString(" · ") { "${it.environmentName}:${it.port}" }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mockonomy_notification_channel_name),
            // The server status must stay visible but must not make sound or vibrate.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mockonomy_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "mockonomy_server"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_STOP = 1
        private const val LOOPBACK = "127.0.0.1"

        const val ACTION_START = "com.mockonomy.server.action.START"
        const val ACTION_STOP = "com.mockonomy.server.action.STOP"
        const val EXTRA_ENVIRONMENT_ID = "com.mockonomy.server.extra.ENVIRONMENT_ID"

        /** Starts the server. As a foreground service it must be called from the foreground. */
        fun start(context: Context, environmentId: String) {
            val intent = Intent(context, MockServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENVIRONMENT_ID, environmentId)
            context.startForegroundService(intent)
        }

        /** Stops all servers when [environmentId] is `null` ("Stop all" in the notification). */
        fun stop(context: Context, environmentId: String?) {
            val intent = Intent(context, MockServerService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ENVIRONMENT_ID, environmentId)
            context.startService(intent)
        }
    }
}
