/*
 * Copyright 2026 Matteo Merli
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.merlimat.nanotime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class NanoClockTest {

    @Test
    void usesHardwareCounterWhereAvailable() throws Exception {
        if (Runtime.version().feature() < 22) {
            assertEquals("System.nanoTime", NanoClock.source());
            return;
        }
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String expected = "System.nanoTime";
        if (os.startsWith("Mac")) {
            expected = arch.equals("aarch64") ? "cntvct_el0" : "mach_absolute_time";
        } else if (os.equals("Linux") && arch.equals("aarch64")) {
            expected = "cntvct_el0";
        } else if (os.equals("Linux") && arch.equals("amd64")) {
            String cpuinfo = Files.readString(Path.of("/proc/cpuinfo"));
            if (cpuinfo.contains(" constant_tsc ") && cpuinfo.contains(" nonstop_tsc ")) {
                expected = "rdtsc";
            }
        }
        assertEquals(expected, NanoClock.source());
    }

    @Test
    void tracksSystemNanoTime() throws Exception {
        // Initialize the class outside the measured interval
        NanoClock.nanoTime();
        long systemStart = System.nanoTime();
        long clockStart = NanoClock.nanoTime();
        Thread.sleep(200);
        long systemElapsed = System.nanoTime() - systemStart;
        long clockElapsed = NanoClock.nanoTime() - clockStart;
        assertEquals(1.0, (double) clockElapsed / systemElapsed, 1e-3);
    }

    @Test
    void neverGoesBackwardsOnOneThread() {
        long previous = NanoClock.nanoTime();
        for (int i = 0; i < 10_000_000; i++) {
            long now = NanoClock.nanoTime();
            assertTrue(now >= previous, () -> "went from " + previous + " to " + now);
        }
    }

    @Test
    void currentTimeMillisTracksSystemClock() throws Exception {
        checkCurrentTimeMillis();
    }

    @Test
    void currentTimeMillisTracksSystemClockOnAllCores() throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    checkCurrentTimeMillis();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
        }
    }

    private static void checkCurrentTimeMillis() throws InterruptedException {
        long samples = 0;
        long behind = 0;
        for (int i = 0; i < 20; i++) {
            // Spin across a few resyncs, then sleep so that the anchor goes stale
            long end = System.nanoTime() + 10_000_000;
            while (System.nanoTime() < end) {
                long before = System.currentTimeMillis();
                long now = NanoClock.currentTimeMillis();
                long after = System.currentTimeMillis();
                // The two clocks can round to different milliseconds near a boundary
                assertTrue(now >= before - 1 && now <= after + 1,
                        () -> now + " is not between " + before + " and " + after);
                samples++;
                if (now < before) {
                    behind++;
                }
            }
            Thread.sleep(10);
        }
        // Without extrapolation from the counter, half the samples would be a millisecond behind
        assertTrue(behind < samples / 100, behind + " of " + samples + " samples were behind");
    }
}
