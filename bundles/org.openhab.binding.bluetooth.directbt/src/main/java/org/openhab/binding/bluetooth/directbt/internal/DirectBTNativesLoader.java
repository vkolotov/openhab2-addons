/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bluetooth.directbt.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts the Direct-BT / jaulib native libraries that are bundled with this binding as classpath
 * resources, copying them onto the JVM's {@code java.library.path} so Direct-BT's own loader can find
 * and {@code System.load()} them by basename.
 * <p>
 * Direct-BT ships a {@code TempJarCache}-based loader, but it relies on locating its containing jar on
 * the filesystem, which is not reliable inside an OSGi framework. We therefore disable it
 * ({@code -Djau.pkg.UseTempJarCache=false}) and pre-extract the libs ourselves. We deliberately do NOT
 * {@code System.load()} them here: a load-by-absolute-path does not satisfy Direct-BT's later
 * load-by-basename (the JVM tracks the two separately), which caused an {@code UnsatisfiedLinkError} —
 * so we only place the files where Direct-BT's loader will find them.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
final class DirectBTNativesLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectBTNativesLoader.class);

    // Dependency order matters: base lib first, then its JNI shims, then direct_bt, then its JNI binding.
    private static final String[] LIBS = { "libjaulib.so", "libjaulib_pkg_jni.so", "libjaulib_jni_jni.so",
            "libdirect_bt.so", "libjavadirect_bt.so" };

    private static boolean loaded;

    private DirectBTNativesLoader() {
    }

    /**
     * Extracts the bundled native libraries to the JVM's {@code java.library.path} directory, so that
     * Direct-BT's own loader ({@code PlatformToolkit.enumerateLibraryPaths}) finds and {@code System.load}s
     * them by basename. We deliberately do NOT {@code System.load} them ourselves: a load-by-absolute-path
     * does not satisfy Direct-BT's later load-by-basename (the JVM tracks them separately), which caused an
     * {@code UnsatisfiedLinkError}. Extracting to {@code java.library.path} lets Direct-BT load them cleanly.
     */
    static synchronized void extractNatives() throws IOException, UnsupportedOperationException {
        if (loaded) {
            return;
        }
        // Process-global: tell Direct-BT's loader not to use its TempJarCache (unreliable in OSGi); it will
        // load from java.library.path instead, where we extract the libs below. Set once.
        System.setProperty("jau.pkg.UseTempJarCache", "false");
        String arch = getNativeArch();
        Path libDir = resolveLibraryPathDir();
        Files.createDirectories(libDir);
        for (String lib : LIBS) {
            String resource = "/native/" + arch + "/" + lib;
            try (InputStream in = DirectBTNativesLoader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Bundled native library not found on classpath: " + resource);
                }
                Path target = libDir.resolve(lib);
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.debug("Extracted native library {}", target);
            }
        }
        loaded = true;
    }

    /**
     * @return a WRITABLE directory on {@code java.library.path} where Direct-BT's loader will then find the
     *         extracted libs by basename. The first entry may be a non-writable system dir (e.g. on a deb
     *         install), so pick the first writable one; fall back to {@code java.io.tmpdir}. NOTE: if no
     *         java.library.path dir is writable, Direct-BT's loader won't search tmpdir and load will fail —
     *         document that the openHAB service must have a writable java.library.path entry.
     */
    private static Path resolveLibraryPathDir() {
        String libPath = Objects.requireNonNullElse(System.getProperty("java.library.path"), "");
        for (String entry : libPath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                File dir = new File(entry);
                if ((dir.isDirectory() && dir.canWrite()) || (!dir.exists() && canCreate(dir))) {
                    return dir.toPath();
                }
            }
        }
        return new File(Objects.requireNonNullElse(System.getProperty("java.io.tmpdir"), "/tmp")).toPath();
    }

    private static boolean canCreate(File dir) {
        File parent = dir.getParentFile();
        return parent != null && parent.isDirectory() && parent.canWrite();
    }

    /**
     * @return the {@code native/<arch>} folder name matching this JVM, mirroring Direct-BT's layout. Only
     *         architectures whose natives are actually bundled with this binding are accepted.
     */
    static String getNativeArch() throws UnsupportedOperationException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!os.startsWith("linux")) {
            throw new UnsupportedOperationException("Direct-BT binding supports Linux only, found: " + os);
        }
        // Only linux-amd64 natives are currently bundled. Add aarch64/arm here once cross-built natives
        // are included under src/main/resources/native/.
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "linux-amd64";
        }
        throw new UnsupportedOperationException(
                "No bundled Direct-BT natives for architecture '" + arch + "' (only linux-amd64 is bundled)");
    }
}
