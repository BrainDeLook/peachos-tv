package com.gathin.tvhome;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import static org.junit.Assert.*;

public class HttpCancellationTest {
    @Test public void cancelledReadDoesNotTouchTheStream() throws Exception {
        InputStream input = new InputStream() {
            @Override public int read() { fail("Cancelled worker must not begin another read"); return -1; }
        };
        Thread.currentThread().interrupt();
        try {
            Http.readAll(input, 100);
            fail("Cancellation was ignored");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
    @Test public void oversizedResponseIsRejected() throws Exception {
        try {
            Http.readAll(new ByteArrayInputStream(new byte[32]), 16);
            fail("Size bound was ignored");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("16"));
        }
    }
    @Test public void anEmptyReadDoesNotTruncateTheResponse() throws Exception {
        InputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            boolean empty = true;
            @Override public int read(byte[] bytes, int offset, int length) {
                if (empty) { empty = false; return 0; }
                return super.read(bytes, offset, length);
            }
        };
        assertArrayEquals(new byte[]{1, 2, 3}, Http.readAll(input, 3));
    }
}
