package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HomeSetupStateTest {
    private static final String OWNER = "com.gathin.tvhome.backdrops";

    @Test public void acceptsInstalledHomePackageNamesWithoutChangingCaseOrPunctuation() {
        for (String packageName : new String[]{"com.google.android.apps.tv.launcherx", "com.vendor.TV_Home", "org._vendor.home2"}) {
            HomeSetupState state = HomeSetupState.requested(packageName, false, OWNER);
            assertEquals(packageName, state.stockPackage);
            assertFalse(state.filterKeys);
        }
    }

    @Test public void blankPackageRepresentsNoInstallerOverride() {
        HomeSetupState state = HomeSetupState.requested("", true, OWNER);
        assertEquals("", state.stockPackage);
        assertTrue(state.filterKeys);
    }

    @Test public void rejectsShellMetacharactersComponentsAndWhitespaceAtTheRequestBoundary() {
        for (String packageName : new String[]{null, " launcher.home", "launcher.home ", "launcher/home", "com.vendor/.Home",
                "com.vendor;reboot", "com.vendor\nhome", "com..home", ".com.home", "com.home.", "com.2home", "home", "com.\u0434\u043e\u043c"}) {
            reject(packageName);
        }
    }

    @Test public void cannotRedirectBackToTheInstallingPackage() {
        reject(OWNER);
    }

    @Test public void rejectsOversizedPackageNamesButAcceptsTheExactLimit() {
        assertEquals(255, HomeSetupState.requested("com." + "a".repeat(251), true, OWNER).stockPackage.length());
        reject("com." + "a".repeat(252));
    }

    @Test public void homeOnlyRedirectDoesNotFilterTheRemoteByDefault() {
        HomeSetupState state = HomeSetupState.requested("com.vendor.home", false, OWNER);
        assertFalse(state.keyFilterEnabled(true, false, false));
    }

    @Test public void explicitSettingsHoldAndRemoteMappingsEnableTheNeededKeyEvents() {
        HomeSetupState state = HomeSetupState.requested("com.vendor.home", false, OWNER);
        assertTrue(state.keyFilterEnabled(true, true, false));
        assertTrue(state.keyFilterEnabled(true, false, true));
        assertFalse(state.keyFilterEnabled(true, false, false));
    }

    @Test public void disabledThemeFilteringRemainsTheMasterGate() {
        for (String packageName : new String[]{"", "com.vendor.home"}) {
            HomeSetupState state = HomeSetupState.requested(packageName, true, OWNER);
            assertFalse(state.keyFilterEnabled(false, true, true));
        }
    }

    @Test public void noOverrideAndExplicitInstallerFilteringKeepTheirExistingBehavior() {
        assertTrue(HomeSetupState.requested("", false, OWNER).keyFilterEnabled(true, false, false));
        assertTrue(HomeSetupState.requested("com.vendor.home", true, OWNER).keyFilterEnabled(true, false, false));
    }

    private static void reject(String packageName) {
        try {
            HomeSetupState.requested(packageName, false, OWNER);
            fail("Accepted invalid package: " + packageName);
        } catch (IllegalArgumentException expected) {
            // Input was rejected before it can reach package lookup or persisted settings.
        }
    }
}
