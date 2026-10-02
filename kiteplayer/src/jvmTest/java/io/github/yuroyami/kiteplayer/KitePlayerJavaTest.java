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

            assertTrue("the open finished before it could be cancelled", open.cancel(true));
            assertTrue(open.isCancelled());
            // Idle comes once the request the open was waiting on gives up, which takes the network
            // reader's ten second read timeout: a cancelled Kotlin open takes as long.
            heard.awaitStatus(PlaybackStatus.Idle);
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
