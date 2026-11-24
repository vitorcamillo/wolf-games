package io.github.vitorcamillo.wolfgames;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** mDNS discovery for the Wolf session controller (_wolf-control._tcp, port 47991). */
final class WolfDiscovery {
    static final String SERVICE_TYPE = "_wolf-control._tcp";
    private static final long CACHE_MS = 60_000;
    private static volatile List<String> cache = null;
    private static volatile long cacheAt = 0;

    static List<String> addresses(Context context, long timeoutMs) {
        try {
            long now = SystemClock.elapsedRealtime();
            List<String> cached = cache;
            if (cached != null && !cached.isEmpty() && now - cacheAt < CACHE_MS) return new ArrayList<>(cached);
            List<String> result = discover(context, timeoutMs);
            if (!result.isEmpty()) {
                cache = new ArrayList<>(result);
                cacheAt = SystemClock.elapsedRealtime();
            }
            return result;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static List<String> discover(Context context, long timeoutMs) {
        List<String> empty = new ArrayList<>();
        try {
            if (context == null) return empty;
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return empty;
            final List<NsdServiceInfo> found = Collections.synchronizedList(new ArrayList<NsdServiceInfo>());
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String serviceType) {}
                @Override public void onDiscoveryStopped(String serviceType) {}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (found.size() < 8) found.add(info);
                }
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
                @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            };
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Exception ignored) {
                return empty;
            }
            try {
                Thread.sleep(Math.max(0, timeoutMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {}
            List<NsdServiceInfo> snapshot;
            synchronized (found) {
                snapshot = new ArrayList<>(found);
            }
            List<String> result = new ArrayList<>();
            for (NsdServiceInfo info : snapshot) {
                if (result.size() >= 8) break;
                try {
                    final CountDownLatch latch = new CountDownLatch(1);
                    final NsdServiceInfo[] resolved = new NsdServiceInfo[1];
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) { latch.countDown(); }
                        @Override public void onServiceResolved(NsdServiceInfo serviceInfo) { resolved[0] = serviceInfo; latch.countDown(); }
                    });
                    latch.await(1500, TimeUnit.MILLISECONDS);
                    NsdServiceInfo done = resolved[0];
                    if (done == null) continue;
                    // Android 14 may resolve to the IPv6 record first; the full list still holds the IPv4 one.
                    List<InetAddress> hosts = new ArrayList<>();
                    if (Build.VERSION.SDK_INT >= 34) hosts.addAll(done.getHostAddresses());
                    else if (done.getHost() != null) hosts.add(done.getHost());
                    for (InetAddress host : hosts) {
                        if (!(host instanceof Inet4Address)) continue;
                        String address = host.getHostAddress();
                        if (address != null && !result.contains(address)) result.add(address);
                    }
                } catch (Exception ignored) {}
            }
            return result;
        } catch (Exception ignored) {
            return empty;
        }
    }
}
