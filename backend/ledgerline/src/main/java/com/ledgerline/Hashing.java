package com.ledgerline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** entry_hash = SHA256(prev | txn | account | direction | amount | balanceAfter | tsMs) */
    public static String entryHash(String prev, Object txn, long account, String dir, long amount, long balanceAfter, long tsMs) {
        return sha256Hex(String.join("|", prev, String.valueOf(txn), String.valueOf(account), dir,
                String.valueOf(amount), String.valueOf(balanceAfter), String.valueOf(tsMs)));
    }
}
