package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkStatusTest {
    private NetworkStatus.Route route(boolean active, boolean vpn, boolean wifi, boolean ethernet, boolean online) {
        return new NetworkStatus.Route(active, vpn, wifi, ethernet, online);
    }
    @Test public void defaultWifiIsNotHiddenByAnOfflineEthernetLink() {
        NetworkStatus status = NetworkStatus.select(List.of(route(false, false, false, true, false), route(true, false, true, false, true)));
        assertEquals(NetworkStatus.Transport.WIFI, status.transport); assertTrue(status.online);
    }
    @Test public void defaultRouteIsRespectedEvenWhenItIsNotValidated() {
        NetworkStatus status = NetworkStatus.select(List.of(route(true, false, false, true, false), route(false, false, true, false, true)));
        assertEquals(NetworkStatus.Transport.ETHERNET, status.transport); assertFalse(status.online);
    }
    @Test public void vpnUnderlyingFlagsDoNotBorrowValidationFromPhysicalNetwork() {
        NetworkStatus status = NetworkStatus.select(List.of(route(true, true, true, false, false), route(false, false, true, false, true)));
        assertEquals(NetworkStatus.Transport.WIFI, status.transport); assertFalse(status.online); assertTrue(status.vpn);
    }
    @Test public void ambiguousVpnNeverAssumesEthernetAndHidesWifi() {
        NetworkStatus status = NetworkStatus.select(List.of(route(true, true, true, true, true),
                route(false, false, true, false, true), route(false, false, false, true, true)));
        assertEquals(NetworkStatus.Transport.OTHER, status.transport); assertTrue(status.online);
    }
    @Test public void vpnWithMissingFlagsUsesOnlyUnambiguousValidatedTransport() {
        NetworkStatus status = NetworkStatus.select(List.of(route(true, true, false, false, true),
                route(false, false, true, false, true), route(false, false, false, true, false)));
        assertEquals(NetworkStatus.Transport.WIFI, status.transport);
    }
    @Test public void noDefaultNetworkIsNotInferredFromAnOldLink() {
        NetworkStatus status = NetworkStatus.select(List.of(route(false, false, false, true, true)));
        assertEquals(NetworkStatus.Transport.NONE, status.transport); assertFalse(status.online);
    }
}
