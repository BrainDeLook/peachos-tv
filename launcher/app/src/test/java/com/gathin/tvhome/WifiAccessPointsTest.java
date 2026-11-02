package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class WifiAccessPointsTest {
    private WifiAccessPoints.Observation ap(String ssid, String bssid, String capabilities, int rssi) {
        return new WifiAccessPoints.Observation(ssid, bssid, capabilities, rssi);
    }

    @Test public void sameSsidAndSecurityCollapseToStrongestRadioWithoutLosingConnectedState() {
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(Arrays.asList(
                ap("Home", "AA:01", "[WPA2-PSK-CCMP][ESS]", -90),
                ap("Home", "AA:02", "[WPA2-PSK-CCMP][ESS]", -50)), "Home", "aa:01");
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).bars);
        assertTrue(rows.get(0).connected);
        assertEquals("Home\u0000PSK", rows.get(0).key);
    }

    @Test public void openAndPasswordNetworksWithSameNameAreSeparateAndBssidSelectsOnlyOne() {
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(Arrays.asList(
                ap("Cafe", "AA:01", "[ESS]", -40),
                ap("Cafe", "AA:02", "[WPA2-PSK-CCMP][ESS]", -80)), "Cafe", "aa:02");
        assertEquals(2, rows.size());
        assertEquals(WifiAccessPoints.Security.PSK, rows.get(0).security);
        assertTrue(rows.get(0).connected);
        assertFalse(rows.get(1).connected);
        assertNotEquals(rows.get(0).key, rows.get(1).key);
    }

    @Test public void presentButUnmatchedBssidDoesNotGuessConnectionFromSsid() {
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(Collections.singletonList(
                ap("Home", "AA:01", "[ESS]", -60)), "Home", "AA:99");
        assertFalse(rows.get(0).connected);
    }

    @Test public void absentBssidFallsBackToExactSsidWithoutTrimmingOrChangingCase() {
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(Arrays.asList(
                ap(" Home ", null, "[ESS]", -60), ap("Home", null, "[ESS]", -60),
                ap("home", null, "[ESS]", -60)), " Home ", null);
        assertEquals(" Home ", rows.get(0).ssid);
        assertTrue(rows.get(0).connected);
        assertFalse(rows.get(1).connected);
        assertFalse(rows.get(2).connected);
    }

    @Test public void hiddenAndUnavailableNamesAreSkippedButWhitespaceAndQuotedNamesArePreserved() {
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(Arrays.asList(
                ap(null, null, "[ESS]", -50), ap("", null, "[ESS]", -50),
                ap("<unknown ssid>", null, "[ESS]", -50),
                ap(" ", null, "[ESS]", -50), ap("\"Home\"", null, "[ESS]", -50)), null, null);
        assertEquals(2, rows.size());
        assertEquals(" ", rows.get(0).ssid);
        assertEquals("\"Home\"", rows.get(1).ssid);
    }

    @Test public void signalBarsSaturateAndDoNotOverflowForInvalidRssiValues() {
        assertEquals(0, ap("a", "", "[ESS]", Integer.MIN_VALUE).bars);
        assertEquals(0, ap("a", "", "[ESS]", -100).bars);
        assertEquals(0, ap("a", "", "[ESS]", -99).bars);
        assertEquals(1, ap("a", "", "[ESS]", -85).bars);
        assertEquals(2, ap("a", "", "[ESS]", -75).bars);
        assertEquals(3, ap("a", "", "[ESS]", -60).bars);
        assertEquals(4, ap("a", "", "[ESS]", -55).bars);
        assertEquals(4, ap("a", "", "[ESS]", Integer.MAX_VALUE).bars);
    }

    @Test public void signalFluctuationsWithinOneBarDoNotShuffleRows() {
        List<WifiAccessPoints.Entry> first = WifiAccessPoints.build(Arrays.asList(
                ap("Zeta", "z", "[ESS]", -72), ap("Alpha", "a", "[ESS]", -76)), null, null);
        List<WifiAccessPoints.Entry> next = WifiAccessPoints.build(Arrays.asList(
                ap("Alpha", "a", "[ESS]", -72), ap("Zeta", "z", "[ESS]", -76)), null, null);
        assertEquals("Alpha", first.get(0).ssid);
        assertEquals(first.get(0).key, next.get(0).key);
        assertEquals(first.get(1).key, next.get(1).key);
        assertEquals(first, next);
        assertEquals(first.hashCode(), next.hashCode());
    }

    @Test public void connectedThenSignalThenNameAndSecurityDefineDeterministicOrder() {
        List<WifiAccessPoints.Observation> scan = Arrays.asList(
                ap("Zeta", "z", "[ESS]", -50), ap("alpha", "a", "[ESS]", -60),
                ap("alpha", "a2", "[WPA2-PSK-CCMP]", -60),
                ap("Connected", "c", "[ESS]", -95));
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(scan, "Connected", "c");
        assertEquals("Connected", rows.get(0).ssid);
        assertEquals("Zeta", rows.get(1).ssid);
        assertEquals(WifiAccessPoints.Security.OPEN, rows.get(2).security);
        assertEquals(WifiAccessPoints.Security.PSK, rows.get(3).security);
        List<WifiAccessPoints.Observation> reversed = new ArrayList<>(scan);
        Collections.reverse(reversed);
        List<WifiAccessPoints.Entry> again = WifiAccessPoints.build(reversed, "Connected", "c");
        for (int index = 0; index < rows.size(); index++) assertEquals(rows.get(index).key, again.get(index).key);
    }

    @Test public void capIsAppliedAfterSortingAndAlwaysKeepsConnectedNetwork() {
        List<WifiAccessPoints.Observation> scan = new ArrayList<>();
        for (int index = 0; index < 50; index++) scan.add(ap("AP" + index, "b" + index, "[ESS]", -50));
        scan.add(ap("Weak connected", "connected", "[ESS]", -99));
        List<WifiAccessPoints.Entry> rows = WifiAccessPoints.build(scan, "Weak connected", "connected");
        assertEquals(32, rows.size());
        assertEquals("Weak connected", rows.get(0).ssid);
    }

    @Test public void pskSaeTransitionUsesCompatiblePskButPureWpa3AndEnhancedOpenRemainDistinct() {
        assertEquals(WifiAccessPoints.Security.PSK, ap("a", "", "[RSN-PSK+SAE-CCMP][ESS]", -60).security);
        assertEquals(WifiAccessPoints.Security.SAE, ap("a", "", "[RSN-SAE-CCMP][ESS]", -60).security);
        assertEquals(WifiAccessPoints.Security.OWE, ap("a", "", "[RSN-OWE-CCMP][ESS]", -60).security);
        assertEquals(WifiAccessPoints.Security.WEP, ap("a", "", "[WEP][ESS]", -60).security);
        assertEquals(WifiAccessPoints.Security.EAP, ap("a", "", "[WPA2-EAP-CCMP][ESS]", -60).security);
    }

    @Test public void unsupportedEnterpriseAndUnknownProtectedNetworksNeverLookOpenOrBasicEap() {
        for (String capabilities : Arrays.asList("[RSN-EAP_SUITE_B_192-GCMP-256][ESS]",
                "[RSN-EAP/SHA256-CCMP][ESS]", "[WPA3-EAP-CCMP][ESS]", "[WAPI-PSK-SMS4]", "[RSN-CCMP]")) {
            WifiAccessPoints.Security security = ap("a", "", capabilities, -60).security;
            assertEquals(WifiAccessPoints.Security.UNKNOWN, security);
            assertTrue(security.secured);
            assertEquals(-1, security.tvSecurity);
        }
        assertEquals(WifiAccessPoints.Security.UNKNOWN, ap("a", "", null, -60).security);
    }

    @Test public void tvConnectorSecurityValuesAreOnlyProvidedForImplementedProtocols() {
        assertEquals(0, WifiAccessPoints.Security.OPEN.tvSecurity);
        assertFalse(WifiAccessPoints.Security.OPEN.secured);
        assertEquals(1, WifiAccessPoints.Security.WEP.tvSecurity);
        assertEquals(2, WifiAccessPoints.Security.PSK.tvSecurity);
        assertEquals(3, WifiAccessPoints.Security.EAP.tvSecurity);
        assertEquals(-1, WifiAccessPoints.Security.SAE.tvSecurity);
        assertEquals(-1, WifiAccessPoints.Security.OWE.tvSecurity);
        assertFalse(WifiAccessPoints.Security.OWE.secured); // Enhanced Open has no password prompt.
    }

    @Test(expected = UnsupportedOperationException.class)
    public void publishedSnapshotCannotBeMutatedByUiConsumers() {
        WifiAccessPoints.build(Collections.singletonList(ap("Home", "", "[ESS]", -60)), null, null).clear();
    }
}
