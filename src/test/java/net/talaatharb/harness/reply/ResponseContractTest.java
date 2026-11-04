package net.talaatharb.harness.reply;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResponseContractTest {
    @Test void defaultsDoNotConstrainReplies() {
        assertTrue(ResponseContract.errors("Anything.", new ReplySettings()).isEmpty());
    }

    @Test void exactListRejectsExtraProseAndWrongCounts() {
        ReplySettings s = new ReplySettings();
        s.exactListItems = 2;
        assertTrue(ResponseContract.errors("1. One\n2. Two", s).isEmpty());
        assertFalse(ResponseContract.errors("Names:\n1. One\n2. Two", s).isEmpty());
        assertFalse(ResponseContract.errors("- One", s).isEmpty());
    }

    @Test void bulletsAreMaximumNotExactAndAllowedRepliesAreCaseSensitive() {
        ReplySettings s = new ReplySettings();
        s.maxBulletPoints = 2;
        assertTrue(ResponseContract.errors("- One", s).isEmpty());
        assertFalse(ResponseContract.errors("- One\n- Two\n- Three", s).isEmpty());
        s.allowedReplies = "YES|NO";
        assertTrue(ResponseContract.errors("YES", s).isEmpty());
        assertFalse(ResponseContract.errors("yes", s).isEmpty());
        assertFalse(ResponseContract.errors("YES because...", s).isEmpty());
    }
}
