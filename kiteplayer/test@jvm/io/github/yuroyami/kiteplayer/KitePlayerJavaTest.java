package io.github.yuroyami.kiteplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioSystem;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The Java layer, used from Java (#394). Nothing in this file is Kotlin.
 *
 * A clip with sound needs an audio device, so on a machine with none the tests play the house
 * clip with no sound instead, which the desktop stack plays on its own clock. Both go through the
 * same Java calls.
 */
public class KitePlayerJavaTest {

    private static final long WAIT_SECONDS = 30;

    private ExecutorService main;

    @Before
    public void startMain() {
        main = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "app-main"));
    }

    @After
    public void stopMain() {
        main.shutdownNow();
    }

    /**
     * A Java app catches the typed error of a platform with no default stack, to fall back to
     * another engine (#913). Without the declaration javac refuses this catch, saying the
     * exception is never thrown, so this file would not compile.
     */
    @Test
    public void aJavaAppCatchesThePlaybackExceptionOfCreate() {
        KitePlayerJava player = null;
        try {
            player = KitePlayerJava.create();
            assertNotNull(player.getPlayer());
        } catch (PlaybackException unavailable) {
            assertFalse("create threw where the platform says it can build a player", KitePlayerJava.isAvailable());
        } finally {
            if (player != null) player.close();
        }
    }

    /**
     * The item builder is the one behind Kotlin's mediaItem block, and each call returns it, so
     * Java chains them (#420). A whole number of milliseconds is a long, as Java writes it.
     */
    @Test
    public void aJavaAppChainsTheItemBuilder() {
        MediaItem item = new MediaItemBuilder("movie.mkv")
                .header("Authorization", "Bearer token")
                .headers(Map.of("X-Session", "42"))
                .startPositionMillis(90_000)
                .formatHint("matroska")
                .lowLatency()
                .title("Movie")
                .artist(null)
                .build();
        assertEquals(Map.of("Authorization", "Bearer token", "X-Session", "42"), item.getHeaders());
        assertEquals("matroska", item.getFormatHint());
        assertTrue(item.getDemux().getLowLatency());
        assertEquals("Movie", item.getTitle());
        assertNull(item.getArtist());
        assertTrue(item.toString(), item.toString().contains("startPosition=1m 30s"));
    }

    @Test
    public void aJavaAppOpensPlaysSeeksAndHearsEachStep() throws Exception {
        Clip clip = clip();
        Heard heard = new Heard();
        KitePlayerJava player = KitePlayerJava.create();
        try {
            player.addListener(heard, main);
            player.openAsync(new MediaItemBuilder(clip.file.getAbsolutePath()).build()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            heard.awaitStatus(PlaybackStatus.Paused);
            assertEquals(Long.valueOf(clip.durationMillis), heard.lastState().getDurationMillis());

            player.getPlayer().play();
            heard.awaitStatus(PlaybackStatus.Playing);

            player.seekAsync(5_000).get(WAIT_SECONDS, TimeUnit.SECONDS);
            PlayerEvent.SeekCompleted seek = heard.awaitEvent(PlayerEvent.SeekCompleted.class);
            assertEquals(5_000.0, seek.getLandedAtMillis(), 100.0);
            assertTrue("the position is " + player.positionMillis(), player.positionMillis() >= 5_000);

            // The test run names a thread after the coroutine it runs as well, so only the start is the executor's.
            assertTrue("heard on " + heard.threads, heard.threads.stream().allMatch(name -> name.startsWith("app-main")));
        } finally {
            player.closeAsync().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * A Java app makes an external clock in milliseconds and the player follows it (#918). The
     * clock says the media should be five seconds in, running from its first answer, so a player
     * that reads it leaves the start and plays on from there.
     */
    @Test
    public void aJavaAppSetsAnExternalClockInMillisecondsAndThePlayerFollowsIt() throws Exception {
        Clip clip = clip();
        Heard heard = new Heard();
        KitePlayerJava player = KitePlayerJava.create();
        AtomicInteger asked = new AtomicInteger();
        long[] first = {Long.MIN_VALUE};
        try {
            player.addListener(heard, main);
            player.openAsync(new MediaItemBuilder(clip.file.getAbsolutePath()).build()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            heard.awaitStatus(PlaybackStatus.Paused);
            player.setExternalClockMillis(atNanos -> {
                asked.incrementAndGet();
                if (first[0] == Long.MIN_VALUE) first[0] = atNanos;
                return 5_000 + (atNanos - first[0]) / 1_000_000;
            });
            player.getPlayer().play();
            heard.awaitProgressPast(4_500);
            assertTrue("the player never asked the clock", asked.get() > 0);
            player.setExternalClockMillis(null);
        } finally {
            player.closeAsync().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * A Java app taps the decoded sound (#970). AudioTap's method takes a Pts, whose value class
     * gives the method a name Java cannot declare, so this implements the Java form instead.
     */
    @Test
    public void aJavaAppTapsTheDecodedSound() throws Exception {
        Clip clip = clip();
        Assume.assumeTrue("the clip with no sound has nothing to tap", clip.file.getName().equals("sync1080p30.mp4"));
        Heard heard = new Heard();
        KitePlayerJava player = KitePlayerJava.create();
        List<Long> times = new CopyOnWriteArrayList<>();
        AtomicInteger frames = new AtomicInteger();
        KitePlayerJava.AudioBlockTap tap = (ptsMicros, interleaved, count, format) -> {
            times.add(ptsMicros);
            frames.addAndGet(count);
        };
        try {
            player.addListener(heard, main);
            player.attachAudioTap(tap);
            player.attachAudioTap(tap);
            player.openAsync(new MediaItemBuilder(clip.file.getAbsolutePath()).build()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            heard.awaitStatus(PlaybackStatus.Paused);
            player.getPlayer().play();
            heard.awaitProgressPast(1_000);
            player.detachAudioTap(tap);
            assertTrue("the tap heard " + times.size() + " blocks", times.size() > 10);
            assertTrue("the tap heard " + frames.get() + " frames", frames.get() > 24_000);
            for (int i = 1; i < times.size(); i++) {
                assertTrue("block " + i + " went back from " + times.get(i - 1) + " to " + times.get(i), times.get(i) > times.get(i - 1));
            }
        } finally {
            player.closeAsync().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aListenerAddedLaterHearsNoEarlierEventAndARemovedOneHearsNothingMore() throws Exception {
        Clip clip = clip();
        PlayerConfigBuilder config = new PlayerConfigBuilder();
        config.setVideoEnabled(true);
        KitePlayerJava player = KitePlayerJava.create(config.build());
        Heard early = new Heard();
        Heard late = new Heard();
        try {
            player.addListener(early, main);
            player.openAsync(new MediaItemBuilder(clip.file.getAbsolutePath()).build()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            early.awaitEvent(PlayerEvent.Opened.class);

            player.addListener(late, main);
            late.awaitStatus(PlaybackStatus.Paused);

            // Removed on the listener's own thread, so no call of it can be running meanwhile.
            int heardBeforeRemoval = main.submit(() -> {
                player.removeListener(early);
                return early.calls.get();
            }).get(WAIT_SECONDS, TimeUnit.SECONDS);

            player.getPlayer().play();
            player.seekAsync(2_000).get(WAIT_SECONDS, TimeUnit.SECONDS);
            late.awaitEvent(PlayerEvent.SeekCompleted.class);
            late.awaitProgressPast(2_500);

            assertFalse("the late listener heard an event from before it was added", late.heardEvent(PlayerEvent.Opened.class));
            assertEquals("the removed listener heard more", heardBeforeRemoval, early.calls.get());
        } finally {
            player.closeAsync().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aCancelledOpenLeavesThePlayerIdleAndReadyForAnother() throws Exception {
        Clip clip = clip();
        Heard heard = new Heard();
        KitePlayerJava player = KitePlayerJava.create();
        // A server that takes the connection and never answers, so the open waits until cancelled.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            player.addListener(heard, main);
            String address = "http://127.0.0.1:" + silent.getLocalPort() + "/movie.mp4";
            CompletableFuture<Void> open = player.openAsync(new MediaItemBuilder(address).build());
            heard.awaitStatus(PlaybackStatus.Opening);

            long cancelledAt = System.nanoTime();
            assertTrue("the open finished before it could be cancelled", open.cancel(true));
            assertTrue(open.isCancelled());
            heard.awaitStatus(PlaybackStatus.Idle);
            // The cancel stops the request the open was waiting on, rather than waiting out the
            // network reader's ten second read timeout (#398).
            long idleAfterMillis = (System.nanoTime() - cancelledAt) / 1_000_000;
            assertTrue("Idle came " + idleAfterMillis + " ms after the cancel", idleAfterMillis < 2_000);
            assertNull(player.getPlayer().getState().getValue().getMedia());

            player.openAsync(new MediaItemBuilder(clip.file.getAbsolutePath()).build()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            heard.awaitStatus(PlaybackStatus.Paused);
        } finally {
            player.closeAsync().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aFailedOpenFailsTheFutureWithThePlayersError() throws Exception {
        KitePlayerJava player = KitePlayerJava.create();
        try {
            CompletableFuture<Void> open = player.openAsync(new MediaItemBuilder("/no/such/file.mp4").build());
            try {
                open.get(WAIT_SECONDS, TimeUnit.SECONDS);
                fail("an open of a file that does not exist completed");
            } catch (ExecutionException failure) {
                assertTrue("the cause is " + failure.getCause(), failure.getCause() instanceof PlaybackException);
                assertNotNull(((PlaybackException) failure.getCause()).getError());
            }
        } finally {
            player.closeAsync().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Records what a listener heard. It overrides two of the four methods, so the others keep their defaults. */
    private static final class Heard implements KitePlayerListener {
        final List<PlayerSnapshot> states = new CopyOnWriteArrayList<>();
        final List<PlayerEvent> events = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        volatile long positionMillis;

        @Override
        public void onState(PlayerSnapshot state) {
            heard();
            states.add(state);
        }

        @Override
        public void onEvent(PlayerEvent event) {
            heard();
            events.add(event);
        }

        @Override
        public void onProgress(Progress progress) {
            heard();
            positionMillis = progress.getPositionMillis();
        }

        private void heard() {
            calls.incrementAndGet();
            threads.add(Thread.currentThread().getName());
        }

        PlayerSnapshot lastState() {
            return states.get(states.size() - 1);
        }

        boolean heardEvent(Class<? extends PlayerEvent> type) {
            return events.stream().anyMatch(type::isInstance);
        }

        /** Waits for [status] after the statuses already matched, so two waits check their order. */
        private int matchedStates = 0;

        void awaitStatus(PlaybackStatus status) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                for (int i = matchedStates; i < states.size(); i++) {
                    if (states.get(i).getStatus() == status) {
                        matchedStates = i + 1;
                        return;
                    }
                }
                Thread.sleep(10);
            }
            fail("never heard " + status + "; heard " + states.stream().map(PlayerSnapshot::getStatus).collect(Collectors.toList()));
        }

        <T extends PlayerEvent> T awaitEvent(Class<T> type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                for (PlayerEvent event : events) {
                    if (type.isInstance(event)) return type.cast(event);
                }
                Thread.sleep(10);
            }
            throw new AssertionError("never heard " + type.getSimpleName() + "; heard " + events);
        }

        void awaitProgressPast(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (positionMillis > millis) return;
                Thread.sleep(10);
            }
            fail("the position never passed " + millis + " ms; it is " + positionMillis);
        }
    }

    private static final class Clip {
        final File file;
        final long durationMillis;

        Clip(File file, long durationMillis) {
            this.file = file;
            this.durationMillis = durationMillis;
        }
    }

    /** The house sync clip where there is an audio device, and the clip with no sound where there is none. */
    private static Clip clip() {
        boolean sound = AudioSystem.getMixerInfo().length > 0;
        String name = sound ? "sync1080p30.mp4" : "sparse-keyframes.mp4";
        String media = System.getenv("KITEPLAYER_TESTMEDIA");
        File file = new File(media != null ? media : "testmedia", name);
        Assume.assumeTrue("no " + name + " to play", file.isFile());
        return new Clip(file, sound ? 10_000 : 12_000);
    }
}
