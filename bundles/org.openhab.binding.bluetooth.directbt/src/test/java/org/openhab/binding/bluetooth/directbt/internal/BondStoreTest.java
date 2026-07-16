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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.binding.bluetooth.BluetoothAddress;

/**
 * File-level tests for {@link BondStore}: lookup must match a persisted bond by the peer's 48-bit address
 * REGARDLESS of the address-type digit in the filename (a bonded peer is saved under its identity type but
 * rediscovered under its advertised type), and deletion must remove exactly that file.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
class BondStoreTest {

    private static final BluetoothAddress PEER = new BluetoothAddress("F1:22:33:44:55:66");

    @TempDir
    @NonNullByDefault({})
    Path dir;

    private Path bondFile(String localHex12, String remoteHex12, int type) throws IOException {
        // The native naming scheme: bd_<local12>_<remote12><typeDigit>.key
        Path f = dir.resolve("bd_" + localHex12 + "_" + remoteHex12 + type + ".key");
        Files.write(f, new byte[] { 1, 2, 3 });
        return f;
    }

    @Test
    void hasMatchesRegardlessOfAddressTypeDigit() throws IOException {
        BondStore store = new BondStore(dir);
        // Saved under PUBLIC identity (type 0) even though the peer advertises RANDOM (type 1).
        bondFile("aabbccddeeff", "f12233445566", 0);

        assertTrue(store.has(PEER), "a bond saved under the identity type must be found by plain address");
    }

    @Test
    void hasIsFalseForADifferentPeerOrEmptyStore() throws IOException {
        BondStore store = new BondStore(dir);
        assertFalse(store.has(PEER), "empty store");

        bondFile("aabbccddeeff", "0102030405ff", 1);
        assertFalse(store.has(PEER), "someone else's bond must not match");
    }

    @Test
    void deleteRemovesTheBondFile() throws IOException {
        BondStore store = new BondStore(dir);
        Path mine = bondFile("aabbccddeeff", "f12233445566", 1);
        Path other = bondFile("aabbccddeeff", "0102030405ff", 1);

        store.delete(PEER);

        assertFalse(Files.exists(mine), "the peer's bond file is gone");
        assertTrue(Files.exists(other), "other bonds are untouched");
        assertFalse(store.has(PEER));
    }

    @Test
    void deleteOnAnEmptyOrMissingDirIsANoOp() {
        BondStore store = new BondStore(dir.resolve("never-created"));
        assertDoesNotThrow(() -> store.delete(PEER));
        assertFalse(store.has(PEER));
    }
}
