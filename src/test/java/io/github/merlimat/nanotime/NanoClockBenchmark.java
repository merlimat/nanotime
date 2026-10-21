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

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class NanoClockBenchmark {

    @Benchmark
    @Threads(1)
    public long systemNanoTime() {
        return System.nanoTime();
    }

    @Benchmark
    @Threads(1)
    public long nanoClock() {
        return NanoClock.nanoTime();
    }

    @Benchmark
    @Threads(Threads.MAX)
    public long systemNanoTimeAllCores() {
        return System.nanoTime();
    }

    @Benchmark
    @Threads(Threads.MAX)
    public long nanoClockAllCores() {
        return NanoClock.nanoTime();
    }
}
