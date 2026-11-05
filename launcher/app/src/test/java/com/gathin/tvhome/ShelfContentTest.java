package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfContentTest {
    private ShelfRepository.Result cards() {
        ShelfRepository.Result result = new ShelfRepository.Result();
        ShelfRepository.Channel channel = new ShelfRepository.Channel();
        channel.id = 7; channel.providerId = "latest"; channel.name = "Latest";
        result.channels.add(channel);
        ShelfRepository.Item item = new ShelfRepository.Item();
        item.title = "Film"; item.image = "https://example.org/poster.jpg"; item.link = "app://film/1";
        item.aspect = 2f / 3f;
        result.items.add(item);
        return result;
    }

    @Test public void unchangedCardsIgnorePlaybackTimestampAndDatabaseIds() {
        ShelfRepository.Result first = cards(), next = cards();
        next.items.get(0).rank = 123456789; next.items.get(0).id = 97;
        assertTrue(ShelfRepository.sameContent(first, next));
    }

    @Test public void artworkAspectAndLinksAreRealChanges() {
        ShelfRepository.Result first = cards(), next = cards();
        next.items.get(0).aspect = 16f / 9f;
        assertFalse(ShelfRepository.sameContent(first, next));
        next = cards(); next.items.get(0).link = "app://film/2";
        assertFalse(ShelfRepository.sameContent(first, next));
        next = cards(); next.items.get(0).image = "https://example.org/new.jpg";
        assertFalse(ShelfRepository.sameContent(first, next));
    }

    @Test public void channelRenameRemovalAndProviderFailureAreRealChanges() {
        ShelfRepository.Result first = cards(), next = cards();
        next.channels.get(0).name = "History";
        assertFalse(ShelfRepository.sameContent(first, next));
        next = cards(); next.items.clear();
        assertFalse(ShelfRepository.sameContent(first, next));
        next = cards(); next.channelsStatus = TvMetadata.Status.TEMPORARILY_UNAVAILABLE;
        assertFalse(ShelfRepository.sameContent(first, next));
        assertFalse(ShelfRepository.sameContent(null, next));
    }

    @Test public void unsupportedWatchNextKeepsPublishedChannelsAndIsNotPermissionDenial() {
        ShelfRepository.Result result = cards();
        result.watchStatus = TvMetadata.failure(new IllegalArgumentException("unknown URI"));
        assertEquals(1, result.channels.size());
        assertEquals("Latest", result.channels.get(0).name);
        assertEquals(TvMetadata.Status.TABLE_UNSUPPORTED, result.issue());
        assertTrue(result.unavailable());
    }

    @Test public void returningAccessIsARealChangeEvenWhenTheProviderStillHasNoCards() {
        ShelfRepository.Result denied = new ShelfRepository.Result(), ready = new ShelfRepository.Result();
        denied.channelsStatus = denied.watchStatus = TvMetadata.Status.PERMISSION_REQUIRED;
        assertFalse(ShelfRepository.sameContent(denied, ready));
    }

    @Test public void temporaryWatchNextFailureDoesNotBecomeMissingPermission() {
        ShelfRepository.Result result = cards();
        result.watchStatus = TvMetadata.failure(new IllegalStateException("provider unavailable"));
        assertEquals(TvMetadata.Status.TEMPORARILY_UNAVAILABLE, result.issue());
        result.previewStatus = TvMetadata.Status.PERMISSION_REQUIRED;
        assertEquals(TvMetadata.Status.PERMISSION_REQUIRED, result.issue());
    }
}
