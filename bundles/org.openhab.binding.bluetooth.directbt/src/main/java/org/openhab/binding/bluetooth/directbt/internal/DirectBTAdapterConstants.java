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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Common constants for the Direct-BT adapter binding.
 *
 * @author Vlad Kolotov - Initial contribution
 */
@NonNullByDefault
public class DirectBTAdapterConstants {

    public static final ThingTypeUID THING_TYPE_DIRECTBT = new ThingTypeUID(BluetoothBindingConstants.BINDING_ID,
            "directbt");

    public static final String PROPERTY_ADDRESS = "address";

    /**
     * Per-device config key (declared on the core generic device thing-type in the generic bundle's
     * {@code generic.xml}) selecting the LE connection security for that one device. Read by
     * {@link DirectBTBridgeHandler#getDeviceConnectionSecurity}.
     */
    public static final String CONFIGURATION_CONNECTION_SECURITY = "connectionSecurity";

    /** Unbonded, no encryption — the proven dead-stable default. */
    public static final String CONNECTION_SECURITY_NONE = "none";

    /**
     * Just-Works LE encryption, STRICT: request encryption and keep retrying if it does not hold; never downgrade.
     * Use for devices that must never talk unencrypted (e.g. locks). Opt-in per device.
     */
    public static final String CONNECTION_SECURITY_ENCRYPTED = "encrypted";

    /**
     * Authenticated (MITM-protected) LE encryption via Passkey Entry: the peripheral displays / has a fixed PIN,
     * and we supply the configured {@link #CONFIGURATION_PASSKEY} when the device asks (SMP PASSKEY_EXPECTED).
     * Uses {@code setConnSecurity(ENC_AUTH, KEYBOARD_ONLY)}. Opt-in per device.
     */
    public static final String CONNECTION_SECURITY_PIN = "pin";

    /** Per-device config key holding the static passkey/PIN (0..999999) for {@link #CONNECTION_SECURITY_PIN}. */
    public static final String CONFIGURATION_PASSKEY = "passkey";

    private DirectBTAdapterConstants() {
    }
}
