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

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import javax.inject.Inject

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.merlimat"
// The release workflow passes the version from the git tag: v0.1.0 -> -PreleaseVersion=0.1.0
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")
description = "Fast alternative to System.nanoTime() that reads the CPU counter through FFM"

repositories {
    mavenCentral()
}

// Multi-release jar: the base classes target Java 17 and fall back to System.nanoTime(), while
// the FFM counters in src/main/java22 go under META-INF/versions/22.
val java22 = sourceSets.create("java22") {
    java.setSrcDirs(listOf("src/main/java22"))
}

val jmhVersion = "1.37"

dependencies {
    "java22Implementation"(files(sourceSets.main.get().output.classesDirs))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>(java22.compileJavaTaskName) {
    options.release = 22
}

tasks.jar {
    into("META-INF/versions/22") {
        from(java22.output)
    }
    manifest {
        attributes("Multi-Release" to "true")
    }
}

// The published Javadoc covers only the public API, NanoClock. Everything else is package-private.
tasks.javadoc {
    options.memberLevel = JavadocMemberLevel.PUBLIC
}

// Tests and benchmarks load the library from the jar, so the JVM picks the classes for its version
val testRuntimeClasspath = files(tasks.jar) + (sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output)

tasks.test {
    useJUnitPlatform()
    classpath = testRuntimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // -PtestJava=17 runs the tests on another Java version, e.g. to check the System.nanoTime()
    // fallback. The build itself still needs Java 22+ to compile src/main/java22.
    providers.gradleProperty("testJava").orNull?.let { version ->
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(version) }
    }
}

tasks.register<JavaExec>("jmh") {
    description = "Runs the JMH benchmarks."
    classpath = testRuntimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    args("NanoClockBenchmark")
}

interface InjectedArchiveOperations {
    @get:Inject val archiveOperations: ArchiveOperations
}

// Self-contained benchmark jar to run on another machine: java -jar nanotime-<version>-jmh.jar
tasks.register<Jar>("jmhJar") {
    description = "Builds a self-contained jar that runs the JMH benchmarks."
    archiveClassifier = "jmh"
    val archives = objects.newInstance<InjectedArchiveOperations>().archiveOperations
    from(testRuntimeClasspath.elements.map { elements ->
        elements.map { if (it.asFile.name.endsWith(".jar")) archives.zipTree(it.asFile) else it.asFile }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The library classes come from its multi-release jar, so this jar must be multi-release too
    manifest {
        attributes("Main-Class" to "org.openjdk.jmh.Main", "Multi-Release" to "true")
    }
}

// Maven Central, through the Central Publisher Portal. Credentials and the signing key come from
// the mavenCentralUsername, mavenCentralPassword and signingInMemoryKey* Gradle properties.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
    pom {
        name = "nanotime"
        description = project.description
        inceptionYear = "2026"
        url = "https://github.com/merlimat/nanotime"
        licenses {
            license {
                name = "Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "merlimat"
                name = "Matteo Merli"
                url = "https://github.com/merlimat"
            }
        }
        scm {
            url = "https://github.com/merlimat/nanotime"
            connection = "scm:git:https://github.com/merlimat/nanotime.git"
            developerConnection = "scm:git:ssh://git@github.com/merlimat/nanotime.git"
        }
    }
}

// Like the jar, the sources jar holds the Java 22 classes' sources under META-INF/versions/22
tasks.named<Jar>("sourcesJar") {
    into("META-INF/versions/22") {
        from(java22.allSource)
    }
}
