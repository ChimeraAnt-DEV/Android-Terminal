package com.chimeraant.terminal.session;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.ui.MainActivity;

/**
 * A foreground service that keeps long-running sandboxes alive when the UI is
 * in the background. It holds no state of its own; {@link SessionManager}
 * remains the single owner of sessions.
 */
public class TerminalService extends Service {

    public static final String ACTION_START = "com.chimeraant.terminal.START";
    public static final String ACTION_STOP = "com.chimeraant.terminal.STOP";
    private static final String CHANNEL_ID = "chimera_sessions";
    private static final int NOTIFICATION_ID = 4711;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel();
        int running = SessionManager.get(this).runningCount();
        startForeground(NOTIFICATION_ID, buildNotification(running));
        return START_STICKY;
    }

    private Notification buildNotification(int running) {
        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text, Math.max(running, 1)))
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.notification_channel_desc));
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
