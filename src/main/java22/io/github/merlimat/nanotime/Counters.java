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

/**
 * Finds the counter for this platform. This is the Java 22+ version of the class, which the jar
 * holds under {@code META-INF/versions/22}.
 */
final class Counters {

    private Counters() {}

    /** Returns the counter for this platform, or null if there is none. */
    static Counter detect() throws Throwable {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        if (os.startsWith("Mac")) {
            return MacCounter.create();
        }
        if (os.equals("Linux") && arch.equals("aarch64")) {
            return Aarch64Counter.create();
        }
        if (os.equals("Linux") && arch.equals("amd64")) {
            return Amd64Counter.create();
        }
        return null;
    }
}
