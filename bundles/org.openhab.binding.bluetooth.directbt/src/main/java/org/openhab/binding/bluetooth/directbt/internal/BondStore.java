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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

import org.direct_bt.BTDevice;
import org.direct_bt.HCIStatusCode;
import org.direct_bt.SMPKeyBin;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists SMP bonds ({@link SMPKeyBin} files) so paired devices survive a full restart / power cycle
 * without re-pairing. One directory per adapter (the LTK binds the peer to this adapter's identity).
 * <p>
 * Saving uses {@code SMPKeyBin.createAndWrite} (the native serializer). Loading deliberately does NOT use
 * {@code BTDevice.setSMPKeyBin}: that validates the bin's remote address INCLUDING the address type, and a
 * peer that distributed an identity during pairing was saved under its identity type (e.g. PUBLIC) while a
 * freshly discovered device is tracked under its advertised type (e.g. static RANDOM) — the strict check
 * would refuse every post-restart bond. Instead the keys are pushed individually via the device's key
 * setters (which carry no address check) and then uploaded. File lookup matches on the 48-bit address only,
 * ignoring the type suffix in the filename, for the same reason.
 * <p>
 * The key files contain LTKs/IRKs — they are secrets. The directory is created owner-only (0700) where the
 * filesystem supports POSIX permissions.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class BondStore {

    private final Logger logger = LoggerFactory.getLogger(BondStore.class);
    private final Path dir;

    public BondStore(Path dir) {
        this.dir = dir;
    }

    /** Persist the device's current SMP keys (call after a pairing has COMPLETED). */
    public void save(BTDevice device) {
        try {
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                // non-POSIX filesystem: nothing to tighten
            }
            // createAndWrite derives the filename from the device's CURRENT (post-pairing, possibly identity)
            // address; lookup tolerates the type difference, see findBondFile().
            if (SMPKeyBin.createAndWrite(device, dir.toString(), false)) {
                logger.debug("Persisted bond for {} to {}", device.getAddressAndType(), dir);
            } else {
                logger.warn("Could not persist bond for {} (no keys to save?)", device.getAddressAndType());
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Persisting bond for {} failed", address(device), e);
        }
    }

    /**
     * Load and upload a persisted bond onto a (not yet connected) device, if one exists for its address.
     * The next connect then reuses the stored keys (PRE_PAIRED) instead of re-pairing.
     *
     * @return true iff a bond file was found, read, and its keys uploaded successfully.
     */
    public boolean apply(BTDevice device) {
        Path file = findBondFile(address(device));
        if (file == null) {
            return false;
        }
        try {
            SMPKeyBin bin = SMPKeyBin.read(file.toString(), false);
            if (!bin.isValid()) {
                logger.warn("Persisted bond {} is invalid; deleting it", file.getFileName());
                deleteQuietly(file);
                return false;
            }
            if (bin.hasLTKInit()) {
                device.setLongTermKey(bin.getLTKInit());
            }
            if (bin.hasLTKResp()) {
                device.setLongTermKey(bin.getLTKResp());
            }
            if (bin.hasIRKInit()) {
                device.setIdentityResolvingKey(bin.getIRKInit());
            }
            if (bin.hasIRKResp()) {
                device.setIdentityResolvingKey(bin.getIRKResp());
            }
            if (bin.hasCSRKInit()) {
                device.setSignatureResolvingKey(bin.getCSRKInit());
            }
            if (bin.hasCSRKResp()) {
                device.setSignatureResolvingKey(bin.getCSRKResp());
            }
            HCIStatusCode rc = device.uploadKeys();
            if (rc != HCIStatusCode.SUCCESS) {
                logger.warn("Uploading persisted bond {} failed: {}", file.getFileName(), rc);
                return false;
            }
            logger.debug("Applied persisted bond {} to {}", file.getFileName(), device.getAddressAndType());
            return true;
        } catch (RuntimeException e) {
            logger.warn("Applying persisted bond {} failed", file.getFileName(), e);
            return false;
        }
    }

    /** @return true iff a persisted bond exists for the address (48-bit match, type-agnostic). */
    public boolean has(BluetoothAddress address) {
        return findBondFile(hex12(address.toString())) != null;
    }

    /**
     * Delete any persisted bond for the address. Must be called whenever the stale-bond self-heal drops the
     * in-memory keys — otherwise the dead bond is resurrected from disk on the next restart, forever.
     */
    public void delete(BluetoothAddress address) {
        Path file = findBondFile(hex12(address.toString()));
        if (file != null) {
            logger.debug("Deleting persisted bond {}", file.getFileName());
            deleteQuietly(file);
        }
    }

    /**
     * Find the bond file for a peer by its 48-bit address, ignoring the address-type suffix the native
     * naming scheme appends ({@code bd_<local12>_<remote12><type>.key}) — the saved type (identity) and the
     * discovered type (advertised) legitimately differ for a bonded peer.
     */
    private @Nullable Path findBondFile(String remoteHex12) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String needle = "_" + remoteHex12.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "bd_*.key")) {
            for (Path f : files) {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                // strip "bd_" prefix + ".key" suffix, then match the remote half (last '_' segment, minus
                // the single type digit at the end)
                int us = name.lastIndexOf('_');
                if (us > 0 && name.regionMatches(us, needle, 0, needle.length())) {
                    return f;
                }
            }
        } catch (IOException e) {
            logger.debug("Scanning bond store {} failed", dir, e);
        }
        return null;
    }

    private static String address(BTDevice device) {
        return hex12(device.getAddressAndType().address.toString());
    }

    private static String hex12(String colonized) {
        return colonized.replace(":", "").toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.warn("Deleting bond file {} failed", file, e);
        }
    }
}
