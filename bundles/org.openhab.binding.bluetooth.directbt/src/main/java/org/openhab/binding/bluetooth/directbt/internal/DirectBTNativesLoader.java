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
import java.util.Objects;
import java.nio.file.StandardCopyOption;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the Direct-BT / jaulib native libraries that are bundled with this binding as classpath
 * resources, by copying each to a temp directory and {@code System.load()}-ing it in dependency order.
 * <p>
 * Direct-BT ships its own {@code TempJarCache}-based native loader, but that relies on locating its
 * containing jar on the filesystem, which is not reliable inside an OSGi framework. Loading the
 * libraries ourselves before {@code BTFactory} initializes guarantees they are present in the JVM
 * regardless; Direct-BT then finds them already loaded. (Same approach as the sputnikdev tinyb
 * transport, where it was the only option.)
 * <p>
 * Set {@code -Djau.pkg.UseTempJarCache=false} so Direct-BT does not also attempt its own extraction.
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

    /** @return the first directory on {@code java.library.path} (where Direct-BT's loader searches). */
    private static Path resolveLibraryPathDir() {
        String libPath = Objects.requireNonNullElse(System.getProperty("java.library.path"), "");
        String dir = Objects.requireNonNullElse(System.getProperty("java.io.tmpdir"), "/tmp");
        for (String entry : libPath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                dir = entry;
                break;
            }
        }
        return new File(dir).toPath();
    }

    /** @return the {@code native/<arch>} folder name matching this JVM, mirroring Direct-BT's layout. */
    static String getNativeArch() throws UnsupportedOperationException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!os.startsWith("linux")) {
            throw new UnsupportedOperationException("Direct-BT binding supports Linux only, found: " + os);
        }
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "linux-amd64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "linux-aarch64";
        }
        if (arch.startsWith("arm")) {
            return "linux-arm";
        }
        throw new UnsupportedOperationException("Unsupported architecture for Direct-BT: " + arch);
    }
}
