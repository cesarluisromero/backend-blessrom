package com.blessrom.commerce;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TokensTest {
    private Map<String,Object> owner(String token, Instant expiry) {
        return Map.of("token_hash", Tokens.hash(token), "expires_at", expiry.toString());
    }

    @Test void generatedTokensHave256BitsAndAreNotRepeated() {
        var tokens = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String token = Tokens.create();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(32, Base64.getUrlDecoder().decode(token).length);
            assertTrue(tokens.add(token), "A token was repeated");
        }
    }

    @Test void issuedTokenAuthenticatesAgainstItsStoredHash() {
        String token = Tokens.create();
        assertDoesNotThrow(() ->
            Tokens.authorize(token, owner(token, Instant.now().plusSeconds(60)), "expires_at"));
    }

    @Test void anotherTokenOrMissingTokenCannotAccessTheCart() {
        var stored = owner(Tokens.create(), Instant.now().plusSeconds(60));
        for (String invalid : new String[] {Tokens.create(), null, "", "x".repeat(201)}) {
            Problem problem = assertThrows(Problem.class, () -> Tokens.authorize(invalid, stored, "expires_at"));
            assertEquals(404, problem.status);
        }
    }

    @Test void expiredTokensCannotAccessTheCart() {
        String token = Tokens.create();
        var stored = owner(token, Instant.now().minusSeconds(1));
        Problem problem = assertThrows(Problem.class, () -> Tokens.authorize(token, stored, "expires_at"));
        assertEquals(404, problem.status);
    }
}
