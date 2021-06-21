package com.mahdiparastesh.mergenvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Medium extends VpnService implements Handler.Callback {
    final String server = "82.102.10.134"; // "" - Server Address
    final int port = 1080; // 0 - Server Port
    final byte[] secret = "".getBytes(); // Shared Secret
    final boolean allow = true;
    final Set<String> packages = Collections.emptySet();
    // if proxyHost.isEmpty() != proxyPort.isEmpty():
    // Incomplete proxy settings. For HTTP proxy we require both hostname and port settings.
    final String proxyHost = ""; // "" - HTTP proxy hostname
    final int proxyPort = 0; // 0 - HTTP proxy port

    private static final String TAG = Medium.class.getSimpleName();
    public static final String ACTION_CONNECT = "com.mahdiparastesh.mergenvpn.START";
    public static final String ACTION_DISCONNECT = "com.mahdiparastesh.mergenvpn.STOP";
    private Handler mHandler;

    private static class Connection extends Pair<Thread, ParcelFileDescriptor> {
        public Connection(Thread thread, ParcelFileDescriptor pfd) {
            super(thread, pfd);
        }
    }

    private final AtomicReference<Thread> mConnectingThread = new AtomicReference<>();
    private final AtomicReference<Connection> mConnection = new AtomicReference<>();
    private final AtomicInteger mNextConnectionId = new AtomicInteger(1);
    private PendingIntent mConfigureIntent;

    @Override
    public void onCreate() {
        if (mHandler == null) mHandler = new Handler(this);
        mConfigureIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, Main.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        } else {
            connect();
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        disconnect();
    }

    @Override
    public boolean handleMessage(Message message) {
        Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
        if (message.what != R.string.disconnected)
            updateForegroundNotification(message.what);
        return true;
    }

    private void connect() {
        updateForegroundNotification(R.string.connecting);
        mHandler.sendEmptyMessage(R.string.connecting);

        startConnection(new Connect(
                this, mNextConnectionId.getAndIncrement(), server, port, secret,
                proxyHost, proxyPort, allow, packages));
    }

    private void startConnection(final Connect connection) {
        final Thread thread = new Thread(connection, "MergenVpnThread");
        setConnectingThread(thread);
        connection.setConfigureIntent(mConfigureIntent);
        connection.setOnEstablishListener(tunInterface -> {
            mHandler.sendEmptyMessage(R.string.connected);
            mConnectingThread.compareAndSet(thread, null);
            setConnection(new Connection(thread, tunInterface));
        });
        thread.start();
    }

    private void setConnectingThread(final Thread thread) {
        final Thread oldThread = mConnectingThread.getAndSet(thread);
        if (oldThread != null) oldThread.interrupt();
    }

    private void setConnection(final Connection connection) {
        final Connection oldConnection = mConnection.getAndSet(connection);
        if (oldConnection != null) try {
            oldConnection.first.interrupt();
            oldConnection.second.close();
        } catch (IOException e) {
            Log.e(TAG, "Closing VPN interface", e);
        }
    }

    private void disconnect() {
        mHandler.sendEmptyMessage(R.string.disconnected);
        setConnectingThread(null);
        setConnection(null);
        stopForeground(true);
    }

    private void updateForegroundNotification(final int message) {
        final String NOTIFICATION_CHANNEL_ID = "MergenVPN";
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        mNotificationManager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(1, new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build());
    }
}