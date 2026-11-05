package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static org.junit.Assert.*;

public class TvMetadataTest {
    private static final class Backend implements TvMetadata.ObserverBackend {
        final List<TvMetadata.Table> attempted = new ArrayList<>();
        final EnumMap<TvMetadata.Table, RuntimeException> failures = new EnumMap<>(TvMetadata.Table.class);
        int removals;
        boolean failRemoval;
        @Override public void register(TvMetadata.Table table) {
            attempted.add(table);
            RuntimeException failure = failures.get(table);
            if (failure != null) throw failure;
        }
        @Override public void unregister() {
            removals++;
            if (failRemoval) throw new IllegalStateException("service disconnected");
        }
    }

    @Test public void missingProviderAndDeniedPermissionNeverRegisterObservers() {
        for (TvMetadata.Status unavailable : new TvMetadata.Status[]{TvMetadata.Status.PROVIDER_MISSING,
                TvMetadata.Status.PERMISSION_REQUIRED}) {
            Backend backend = new Backend();
            TvMetadata.Observation observation = new TvMetadata.Observation(unavailable, backend,
                    TvMetadata.Table.CHANNELS, TvMetadata.Table.WATCH_NEXT);
            assertTrue(backend.attempted.isEmpty());
            assertEquals(unavailable, observation.status(TvMetadata.Table.CHANNELS));
            assertFalse(observation.live());
            assertFalse(observation.complete());
            observation.close();
            assertEquals(0, backend.removals);
        }
    }

    @Test public void unsupportedWatchNextDoesNotDiscardChannelObserverAndClosesPartialRegistrationOnce() {
        Backend backend = new Backend();
        backend.failures.put(TvMetadata.Table.WATCH_NEXT, new IllegalArgumentException("unknown URI"));
        TvMetadata.Observation observation = new TvMetadata.Observation(TvMetadata.Status.READY, backend,
                TvMetadata.Table.CHANNELS, TvMetadata.Table.WATCH_NEXT);
        assertEquals(TvMetadata.Status.READY, observation.status(TvMetadata.Table.CHANNELS));
        assertEquals(TvMetadata.Status.TABLE_UNSUPPORTED, observation.status(TvMetadata.Table.WATCH_NEXT));
        assertTrue(observation.live());
        assertFalse(observation.complete());
        observation.close(); observation.close();
        assertEquals(1, backend.removals);
        assertFalse(observation.live());
    }

    @Test public void failingFirstTableStillAllowsIndependentTablesToObserve() {
        Backend backend = new Backend();
        backend.failures.put(TvMetadata.Table.CHANNELS, new SecurityException("permission revoked"));
        TvMetadata.Observation observation = new TvMetadata.Observation(TvMetadata.Status.READY, backend,
                TvMetadata.Table.CHANNELS, TvMetadata.Table.PREVIEW, TvMetadata.Table.WATCH_NEXT);
        assertEquals(3, backend.attempted.size());
        assertEquals(TvMetadata.Status.PERMISSION_REQUIRED, observation.status(TvMetadata.Table.CHANNELS));
        assertEquals(TvMetadata.Status.READY, observation.status(TvMetadata.Table.PREVIEW));
        observation.close();
        assertEquals(1, backend.removals);
    }

    @Test public void fullyRejectedObserverDoesNotUnregisterAnotherOwnersObserver() {
        Backend backend = new Backend();
        backend.failures.put(TvMetadata.Table.CHANNELS, new SecurityException("denied"));
        TvMetadata.Observation observation = new TvMetadata.Observation(TvMetadata.Status.READY, backend,
                TvMetadata.Table.CHANNELS);
        observation.close();
        assertEquals(0, backend.removals);
    }

    @Test public void providerDisappearingDuringCleanupCannotCrashOrCauseRepeatedUnregister() {
        Backend backend = new Backend();
        TvMetadata.Observation observation = new TvMetadata.Observation(TvMetadata.Status.READY, backend,
                TvMetadata.Table.CHANNELS);
        backend.failRemoval = true;
        observation.close(); observation.close();
        assertEquals(1, backend.removals);
        assertFalse(observation.complete());
    }

    @Test public void successfulRegistrationRemainsCompleteUntilClosed() {
        Backend backend = new Backend();
        TvMetadata.Observation observation = new TvMetadata.Observation(TvMetadata.Status.READY, backend,
                TvMetadata.Table.CHANNELS, TvMetadata.Table.WATCH_NEXT);
        assertTrue(observation.complete());
        observation.close();
        assertFalse(observation.complete());
    }

    @Test public void schemaTransportAndPermissionFailuresHaveDifferentRecoveryPaths() {
        assertEquals(TvMetadata.Status.TABLE_UNSUPPORTED, TvMetadata.failure(new IllegalArgumentException()));
        assertEquals(TvMetadata.Status.TEMPORARILY_UNAVAILABLE, TvMetadata.failure(new IllegalStateException()));
        assertEquals(TvMetadata.Status.PERMISSION_REQUIRED, TvMetadata.failure(new SecurityException()));
    }
}
