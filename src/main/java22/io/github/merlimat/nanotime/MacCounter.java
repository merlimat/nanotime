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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * macOS x86_64, and arm64 when the process is not allowed to run unsigned code:
 * {@code mach_absolute_time()}, scaled by {@code mach_timebase_info()}. This skips the global CAS
 * that HotSpot does in {@code System.nanoTime()} to enforce monotonicity.
 */
final class MacCounter {

    private MacCounter() {}

    static Counter create() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        MethodHandle ticks = linker.downcallHandle(
                libc.find("mach_absolute_time").orElseThrow(),
                FunctionDescriptor.of(JAVA_LONG),
                Linker.Option.critical(false));
        MethodHandle timebaseInfo = linker.downcallHandle(
                libc.find("mach_timebase_info").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            // struct mach_timebase_info { uint32_t numer; uint32_t denom; }
            MemorySegment info = arena.allocate(8);
            if ((int) timebaseInfo.invokeExact(info) != 0) {
                throw new IllegalStateException("mach_timebase_info failed");
            }
            double nanosPerTick = (double) info.get(JAVA_INT, 0) / info.get(JAVA_INT, 4);
            return new Counter("mach_absolute_time", ticks, nanosPerTick);
        }
    }
}
