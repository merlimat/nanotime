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
