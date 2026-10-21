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
 * Turns a few bytes of machine code into a function that can be called through FFM. Linux, macOS
 * and Windows.
 */
final class ExecutableMemory {

    private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final int PROT_READ = 1;
    private static final int PROT_WRITE = 2;
    private static final int PROT_EXEC = 4;
    private static final int MAP_PRIVATE = 0x02;
    private static final int MAP_ANONYMOUS = MAC ? 0x1000 : 0x20;
    // macOS csops(), from <sys/codesign.h>
    private static final int CS_OPS_STATUS = 0;
    private static final int CS_ENFORCEMENT = 0x1000;
    // Windows VirtualAlloc() and VirtualProtect(), from <winnt.h>
    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_RESERVE = 0x2000;
    private static final int PAGE_READWRITE = 0x04;
    private static final int PAGE_EXECUTE_READ = 0x20;

    private ExecutableMemory() {}

    /** Copies machine code into a new page, then flips the page from writable to executable. */
    static MemorySegment map(MemorySegment code) throws Throwable {
        if (WINDOWS) {
            return mapOnWindows(code);
        }
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        if (MAC) {
            checkUnsignedCodeAllowed(linker, libc);
        }
        MethodHandle mmap = linker.downcallHandle(
                libc.find("mmap").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        MethodHandle mprotect = linker.downcallHandle(
                libc.find("mprotect").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));

        long size = code.byteSize();
        MemorySegment page = (MemorySegment) mmap.invokeExact(
                MemorySegment.NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0L);
        if (page.address() == -1L) {
            throw new IllegalStateException("mmap failed");
        }
        page = page.reinterpret(size);
        page.copyFrom(code);
        if ((int) mprotect.invokeExact(page, size, PROT_READ | PROT_EXEC) != 0) {
            throw new IllegalStateException("mprotect failed");
        }
        if (MAC) {
            // Apple's rule for generated code: invalidate the instruction cache before running it
            MethodHandle icacheInvalidate = linker.downcallHandle(
                    libc.find("sys_icache_invalidate").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
            icacheInvalidate.invokeExact(page, size);
        }
        return page;
    }

    /** Windows version of {@link #map}, with kernel32's VirtualAlloc() and VirtualProtect(). */
    private static MemorySegment mapOnWindows(MemorySegment code) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle virtualAlloc = linker.downcallHandle(
                kernel32.find("VirtualAlloc").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
        MethodHandle virtualProtect = linker.downcallHandle(
                kernel32.find("VirtualProtect").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle getCurrentProcess = linker.downcallHandle(
                kernel32.find("GetCurrentProcess").orElseThrow(), FunctionDescriptor.of(ADDRESS));
        MethodHandle flushInstructionCache = linker.downcallHandle(
                kernel32.find("FlushInstructionCache").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));

        long size = code.byteSize();
        MemorySegment page = (MemorySegment)
                virtualAlloc.invokeExact(MemorySegment.NULL, size, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
        if (page.address() == 0) {
            throw new IllegalStateException("VirtualAlloc failed");
        }
        page = page.reinterpret(size);
        page.copyFrom(code);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment oldProtect = arena.allocate(JAVA_INT);
            if ((int) virtualProtect.invokeExact(page, size, PAGE_EXECUTE_READ, oldProtect) == 0) {
                throw new IllegalStateException("VirtualProtect failed");
            }
        }
        // Microsoft's rule for generated code: flush the instruction cache before running it
        MemorySegment process = (MemorySegment) getCurrentProcess.invokeExact();
        if ((int) flushInstructionCache.invokeExact(process, page, size) == 0) {
            throw new IllegalStateException("FlushInstructionCache failed");
        }
        return page;
    }

    /**
     * Throws if macOS enforces code signing on this process, which happens with the hardened runtime
     * but without the {@code com.apple.security.cs.allow-unsigned-executable-memory} entitlement.
     * There, mprotect() still succeeds, but the kernel kills the process when it runs the page.
     */
    private static void checkUnsignedCodeAllowed(Linker linker, SymbolLookup libc) throws Throwable {
        MethodHandle csops = linker.downcallHandle(
                libc.find("csops").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment flags = arena.allocate(JAVA_INT);
            // pid 0 is this process
            if ((int) csops.invokeExact(0, CS_OPS_STATUS, flags, flags.byteSize()) != 0) {
                throw new IllegalStateException("csops failed");
            }
            if ((flags.get(JAVA_INT, 0) & CS_ENFORCEMENT) != 0) {
                throw new IllegalStateException("macOS does not allow this process to run unsigned code");
            }
        }
    }
}
