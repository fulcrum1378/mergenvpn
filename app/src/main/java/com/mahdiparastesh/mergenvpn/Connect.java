package com.mahdiparastesh.mergenvpn;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class Connect extends Thread {
    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }

    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long RECEIVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);

    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;
    private final VpnService mService;
    private final int mConnectionId;
    private final String mServerName;
    private final int mServerPort;
    private final byte[] mSharedSecret;
    private PendingIntent mConfigureIntent;
    private OnEstablishListener mOnEstablishListener;
    private String mProxyHostName;
    private int mProxyHostPort;
    private final boolean mAllow;
    private final Set<String> mPackages;

    public Connect(final VpnService service, final int connectionId,
                   final String serverName, final int serverPort, final byte[] sharedSecret,
                   final String proxyHostName, final int proxyHostPort, boolean allow,
                   final Set<String> packages) {
        mService = service;
        mConnectionId = connectionId;
        mServerName = serverName;
        mServerPort = serverPort;
        mSharedSecret = sharedSecret;
        if (!TextUtils.isEmpty(proxyHostName)) {
            mProxyHostName = proxyHostName;
        }
        if (proxyHostPort > 0) mProxyHostPort = proxyHostPort;
        mAllow = allow;
        mPackages = packages;
    }

    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }

    public void setOnEstablishListener(OnEstablishListener listener) {
        mOnEstablishListener = listener;
    }

    @Override
    public void run() {
        try {
            Log.i(getTag(), "Starting");
            final SocketAddress serverAddress = new InetSocketAddress(mServerName, mServerPort);
            for (int attempt = 0; attempt < 10; ++attempt) {
                if (run(serverAddress)) attempt = 0;
                Thread.sleep(3000);
            }
            Log.i(getTag(), "Giving up");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            Log.e(getTag(), "Connection failed, exiting", e);
        }
    }

    private boolean run(SocketAddress server)
            throws IOException, InterruptedException, IllegalArgumentException {
        ParcelFileDescriptor iface = null;
        boolean connected = false;
        try (DatagramChannel tunnel = DatagramChannel.open()) {
            if (!mService.protect(tunnel.socket()))
                throw new IllegalStateException("Cannot protect the tunnel");

            tunnel.connect(server);
            tunnel.configureBlocking(false);
            iface = handshake(tunnel);
            connected = true;
            FileInputStream in = new FileInputStream(iface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(iface.getFileDescriptor());
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);
            long lastSendTime = System.currentTimeMillis();
            long lastReceiveTime = System.currentTimeMillis();

            while (true) {
                boolean idle = true;
                int length = in.read(packet.array());
                if (length > 0) {
                    packet.limit(length);
                    tunnel.write(packet);
                    packet.clear();
                    idle = false;
                    lastReceiveTime = System.currentTimeMillis();
                }
                length = tunnel.read(packet);
                if (length > 0) {
                    if (packet.get(0) != 0)
                        out.write(packet.array(), 0, length);
                    packet.clear();
                    idle = false;
                    lastSendTime = System.currentTimeMillis();
                }
                if (idle) {
                    Thread.sleep(IDLE_INTERVAL_MS);
                    final long timeNow = System.currentTimeMillis();
                    if (lastSendTime + KEEPALIVE_INTERVAL_MS <= timeNow) {
                        packet.put((byte) 0).limit(1);
                        for (int i = 0; i < 3; ++i) {
                            packet.position(0);
                            tunnel.write(packet);
                        }
                        packet.clear();
                        lastSendTime = timeNow;
                    } else if (lastReceiveTime + RECEIVE_TIMEOUT_MS <= timeNow)
                        throw new IllegalStateException("Timed out");
                }
            }
        } catch (SocketException e) {
            Log.e(getTag(), "Cannot use socket", e);
        } finally {
            if (iface != null) try {
                iface.close();
            } catch (IOException e) {
                Log.e(getTag(), "Unable to close interface", e);
            }
        }
        return connected;
    }

    private ParcelFileDescriptor handshake(DatagramChannel tunnel)
            throws IOException, InterruptedException {
        ByteBuffer packet = ByteBuffer.allocate(1024);
        packet.put((byte) 0).put(mSharedSecret).flip();
        for (int i = 0; i < 3; ++i) {
            packet.position(0);
            tunnel.write(packet);
        }
        packet.clear();
        for (int i = 0; i < MAX_HANDSHAKE_ATTEMPTS; ++i) {
            Thread.sleep(IDLE_INTERVAL_MS);
            int length = tunnel.read(packet);
            if (length > 0 && packet.get(0) == 0)
                return configure(new String(packet.array(), 1, length - 1, US_ASCII).trim());
        }
        throw new IOException("Timed out");
    }

    private ParcelFileDescriptor configure(String parameters) throws IllegalArgumentException {
        VpnService.Builder builder = mService.new Builder();
        for (String parameter : parameters.split(" ")) {
            String[] fields = parameter.split(",");
            try {
                switch (fields[0].charAt(0)) {
                    case 'm':
                        builder.setMtu(Short.parseShort(fields[1]));
                        break;
                    case 'a':
                        builder.addAddress(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case 'r':
                        builder.addRoute(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case 'd':
                        builder.addDnsServer(fields[1]);
                        break;
                    case 's':
                        builder.addSearchDomain(fields[1]);
                        break;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad parameter: " + parameter);
            }
        }
        final ParcelFileDescriptor vpnInterface;
        for (String packageName : mPackages) {
            try {
                if (mAllow) builder.addAllowedApplication(packageName);
                else builder.addDisallowedApplication(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(getTag(), "Package not available: " + packageName, e);
            }
        }
        builder.setSession(mServerName).setConfigureIntent(mConfigureIntent);
        if (!TextUtils.isEmpty(mProxyHostName))
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(mProxyHostName, mProxyHostPort));

        synchronized (mService) {
            vpnInterface = builder.establish();
            if (mOnEstablishListener != null)
                mOnEstablishListener.onEstablish(vpnInterface);
        }
        Log.i(getTag(), "New interface: " + vpnInterface + " (" + parameters + ")");
        return vpnInterface;
    }

    private final String getTag() {
        return Connect.class.getSimpleName() + "[" + mConnectionId + "]";
    }
}
