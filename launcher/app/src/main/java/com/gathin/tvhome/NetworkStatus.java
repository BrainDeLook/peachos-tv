package com.gathin.tvhome;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Default route and physical connectivity are different facts, especially with VPN or captive LAN. */
final class NetworkStatus {
    enum Transport { NONE, WIFI, ETHERNET, OTHER }
    static final class Route {
        final boolean active, vpn, wifi, ethernet, online;
        Route(boolean active, boolean vpn, boolean wifi, boolean ethernet, boolean online) {
            this.active = active; this.vpn = vpn; this.wifi = wifi; this.ethernet = ethernet; this.online = online;
        }
    }
    final Transport transport;
    final boolean online, vpn;
    NetworkStatus(Transport transport, boolean online, boolean vpn) { this.transport = transport; this.online = online; this.vpn = vpn; }

    static NetworkStatus read(ConnectivityManager manager) {
        List<Route> routes = new ArrayList<>();
        if (manager != null) {
            try {
                Network active = manager.getActiveNetwork();
                for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    if (caps != null) routes.add(new Route(network.equals(active), caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
                }
                // A network can appear between the default-route and enumeration calls.
                if (active != null && routes.stream().noneMatch(route -> route.active)) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                    if (caps != null) routes.add(new Route(true, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
                }
            } catch (RuntimeException unavailable) { Log.w("PeachNetwork", "Network status unavailable", unavailable); }
        }
        return select(routes);
    }

    static NetworkStatus select(List<Route> routes) {
        Route active = null;
        boolean vpn = false;
        for (Route route : routes) { if (route.active) active = route; vpn |= route.vpn; }
        if (active == null) return new NetworkStatus(Transport.NONE, false, vpn);
        Transport selected = physical(active);
        if (active.vpn && selected == Transport.OTHER) {
            // Some VPNs omit/aggregate underlying flags. Only an unambiguous validated physical
            // transport is useful; guessing Ethernet here would hide a working Wi-Fi selector.
            Transport candidate = Transport.NONE;
            boolean ambiguous = false;
            for (Route route : routes) {
                if (route.vpn || !route.online) continue;
                Transport transport = physical(route);
                if (transport == Transport.OTHER) continue;
                if (candidate != Transport.NONE && candidate != transport) ambiguous = true;
                candidate = transport;
            }
            if (!ambiguous && candidate != Transport.NONE) selected = candidate;
        }
        return new NetworkStatus(selected, active.online, vpn);
    }

    private static Transport physical(Route route) {
        if (route.wifi == route.ethernet) return Transport.OTHER;
        return route.ethernet ? Transport.ETHERNET : Transport.WIFI;
    }
}
