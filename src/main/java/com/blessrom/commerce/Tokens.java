package com.blessrom.commerce;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
final class Tokens {
    private static final SecureRandom RANDOM=new SecureRandom();
    static String create() { byte[] value=new byte[32];RANDOM.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void authorize(String token,Map<String,Object> row,String expiryKey) {
        if(token==null||token.length()>200||!Instant.parse(row.get(expiryKey).toString()).isAfter(Instant.now())||
            !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.UTF_8),row.get("token_hash").toString().getBytes(StandardCharsets.UTF_8))) throw Problem.missing();
    }
}

