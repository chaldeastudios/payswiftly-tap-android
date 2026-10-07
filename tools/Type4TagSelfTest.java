import ke.co.payswiftly.tap.Type4Tag;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Plays the part of a phone reading a Type 4 NFC tag, against the same
 * Type4Tag class the app uses. Run with tools/selftest.sh.
 * No test framework: exits non-zero on the first failed check.
 */
public class Type4TagSelfTest {
    static int checks = 0;

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("  ok  " + what);
    }

    static byte[] b(int... v) {
        byte[] o = new byte[v.length];
        for (int i = 0; i < v.length; i++) o[i] = (byte) v[i];
        return o;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static String hex(byte[] a) {
        StringBuilder s = new StringBuilder();
        for (byte x : a) s.append(String.format("%02X ", x));
        return s.toString().trim();
    }

    static boolean isOk(byte[] r) {
        return r.length >= 2 && r[r.length - 2] == (byte) 0x90 && r[r.length - 1] == 0x00;
    }

    static byte[] body(byte[] r) {
        return Arrays.copyOf(r, r.length - 2);
    }

    static boolean sw(byte[] r, int hi, int lo) {
        return r.length == 2 && (r[0] & 0xFF) == hi && (r[1] & 0xFF) == lo;
    }

    /** Independent NDEF parser: returns the URL from a single short URI record. */
    static String parseUri(byte[] msg) {
        int hdr = msg[0] & 0xFF;
        boolean mb = (hdr & 0x80) != 0, me = (hdr & 0x40) != 0, cf = (hdr & 0x20) != 0;
        boolean sr = (hdr & 0x10) != 0, il = (hdr & 0x08) != 0;
        int tnf = hdr & 0x07;
        if (!mb || !me || cf || !sr || il || tnf != 1) throw new IllegalStateException("bad header " + hdr);
        int typeLen = msg[1] & 0xFF, payLen = msg[2] & 0xFF;
        if (typeLen != 1 || msg[3] != 'U') throw new IllegalStateException("not a URI record");
        if (msg.length != 3 + typeLen + payLen) throw new IllegalStateException("length mismatch");
        String[] prefixes = {"", "http://www.", "https://www.", "http://", "https://"};
        int code = msg[4] & 0xFF;
        return prefixes[code] + new String(msg, 5, payLen - 1, StandardCharsets.US_ASCII);
    }

    /** What Android's NFC stack does when it meets a Type 4 NDEF tag. */
    static byte[] readLikeAndroid(Type4Tag tag) {
        byte[] r = tag.process(cat(b(0x00, 0xA4, 0x04, 0x00, 0x07), Type4Tag.AID, b(0x00)));
        check(isOk(r), "reader: SELECT NDEF application -> 9000");
        r = tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x03));
        check(isOk(r), "reader: SELECT capability file E103 -> 9000");
        r = tag.process(b(0x00, 0xB0, 0x00, 0x00, 0x0F));
        check(isOk(r) && body(r).length == 15, "reader: READ capability file -> 15 bytes + 9000");
        byte[] cc = body(r);
        check(cc[0] == 0x00 && cc[1] == 0x0F, "capability: CCLEN is 15");
        check(cc[2] == 0x20, "capability: mapping version 2.0");
        check(cc[7] == 0x04 && cc[8] == 0x06, "capability: NDEF file-control TLV is T=04 L=06");
        check((cc[9] & 0xFF) == 0xE1 && cc[10] == 0x04, "capability: NDEF file id is E104");
        check(cc[11] == 0x00 && (cc[12] & 0xFF) >= 0x05, "capability: max NDEF size is sane");
        check(cc[13] == 0x00 && (cc[14] & 0xFF) == 0xFF, "capability: readable, not writable");
        r = tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04));
        check(isOk(r), "reader: SELECT NDEF file E104 -> 9000");
        r = tag.process(b(0x00, 0xB0, 0x00, 0x00, 0x02));
        check(isOk(r) && body(r).length == 2, "reader: READ NLEN -> 2 bytes + 9000");
        int nlen = ((body(r)[0] & 0xFF) << 8) | (body(r)[1] & 0xFF);
        r = tag.process(b(0x00, 0xB0, 0x00, 0x02, nlen));
        check(isOk(r) && body(r).length == nlen, "reader: READ message (" + nlen + " bytes) -> 9000");
        return body(r);
    }

    public static void main(String[] args) {
        System.out.println("== known vector (worked out by hand from the NFC Forum URI spec) ==");
        byte[] rec = Type4Tag.buildUriRecord("https://example.com");
        byte[] expect = cat(b(0xD1, 0x01, 0x0C, 0x55, 0x04), "example.com".getBytes(StandardCharsets.US_ASCII));
        check(Arrays.equals(rec, expect), "https://example.com encodes to " + hex(expect));
        byte[] httpRec = Type4Tag.buildUriRecord("http://a.co");
        check(httpRec[4] == 0x03, "http:// uses prefix code 03");

        System.out.println("== full tap, the way Android reads it ==");
        final int[] done = {0};
        final StringBuilder log = new StringBuilder();
        Type4Tag.Listener l = new Type4Tag.Listener() {
            public void onEvent(String e) { log.append(e).append("; "); }
            public void onNdefReadComplete() { done[0]++; }
        };
        String url = "https://payswiftly.co.ke/r/3f2b8c1e-9a4d-4e57-b6a0-1c2d3e4f5a6b";
        Type4Tag tag = new Type4Tag(url, l);
        byte[] msg = readLikeAndroid(tag);
        check(parseUri(msg).equals(url), "independent parser recovers the exact payment link");
        check(done[0] == 1, "completion callback fired exactly once");

        System.out.println("== a second tap on the same tag object (reader returns) ==");
        tag.reset();
        msg = readLikeAndroid(tag);
        check(parseUri(msg).equals(url), "second tap after reset reads the same link");
        check(done[0] == 2, "completion callback fired for the second tap");

        System.out.println("== one-shot read of the whole file (some readers do this) ==");
        tag = new Type4Tag(url, l);
        tag.process(cat(b(0x00, 0xA4, 0x04, 0x00, 0x07), Type4Tag.AID));
        tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04));
        byte[] r = tag.process(b(0x00, 0xB0, 0x00, 0x00, 0x00)); // Le=0 means "up to 256"
        check(isOk(r), "READ with Le=00 returns whatever is available + 9000");
        byte[] file = body(r);
        int nlen = ((file[0] & 0xFF) << 8) | (file[1] & 0xFF);
        check(file.length == 2 + nlen, "whole file is NLEN + message with no padding");
        check(parseUri(Arrays.copyOfRange(file, 2, file.length)).equals(url), "link intact in one-shot read");

        System.out.println("== chunked read (reader asks for small pieces) ==");
        tag = new Type4Tag(url, l);
        tag.process(cat(b(0x00, 0xA4, 0x04, 0x00, 0x07), Type4Tag.AID));
        tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04));
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        int off = 0;
        while (true) {
            r = tag.process(b(0x00, 0xB0, off >> 8, off & 0xFF, 16));
            check(isOk(r), "chunk at offset " + off);
            byte[] part = body(r);
            got.write(part, 0, part.length);
            off += part.length;
            if (part.length < 16) break;
        }
        byte[] all = got.toByteArray();
        check(parseUri(Arrays.copyOfRange(all, 2, all.length)).equals(url), "link intact after 16-byte chunks");

        System.out.println("== things a reader must be refused ==");
        tag = new Type4Tag(url, l);
        check(sw(tag.process(b(0x00, 0xB0, 0x00, 0x00, 0x02)), 0x69, 0x85), "READ before selecting anything -> 6985");
        check(sw(tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04)), 0x6A, 0x82), "SELECT file before the app -> 6A82");
        check(sw(tag.process(cat(b(0x00, 0xA4, 0x04, 0x00, 0x07), b(0xA0, 0, 0, 0, 3, 0x10, 0x10))), 0x6A, 0x82), "SELECT a payment-card AID -> 6A82 (we never pose as a card)");
        tag.process(cat(b(0x00, 0xA4, 0x04, 0x00, 0x07), Type4Tag.AID));
        check(sw(tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0x12, 0x34)), 0x6A, 0x82), "SELECT unknown file -> 6A82");
        tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04));
        check(sw(tag.process(b(0x00, 0xD6, 0x00, 0x00, 0x02, 0x00, 0x00)), 0x69, 0x85), "UPDATE BINARY (write) refused -> 6985");
        check(sw(tag.process(b(0x00, 0xB0, 0x7F, 0xFF, 0x02)), 0x6B, 0x00), "READ far past the end -> 6B00");
        check(sw(tag.process(b(0x00, 0xCA, 0x00, 0x00, 0x00)), 0x6D, 0x00), "unknown instruction -> 6D00");
        check(sw(tag.process(b(0x80, 0xB0, 0x00, 0x00, 0x02)), 0x6E, 0x00), "proprietary class -> 6E00");
        check(sw(tag.process(b(0x00, 0xB0)), 0x67, 0x00), "malformed (2-byte) command -> 6700");
        tag.reset();
        check(sw(tag.process(b(0x00, 0xB0, 0x00, 0x00, 0x02)), 0x69, 0x85), "after reset, READ needs a fresh SELECT");

        System.out.println("== link validation ==");
        String longUrl = "https://payswiftly.co.ke/r/" + "a".repeat(Type4Tag.MAX_URL_CHARS);
        expectReject(longUrl, "link over " + Type4Tag.MAX_URL_CHARS + " chars");
        expectReject("payswiftly.co.ke/r/abc", "link without https://");
        expectReject("ftp://payswiftly.co.ke/r/abc", "non-http scheme");
        expectReject("https://", "empty address");
        expectReject("https://payswiftly.co.ke/r/a b", "link with a space");
        expectReject("https://payswiftly.co.ke/r/é", "non-ASCII character");
        expectReject("", "empty link");
        String maxUrl = "https://" + "a".repeat(Type4Tag.MAX_URL_CHARS - 8);
        Type4Tag big = new Type4Tag(maxUrl, null);
        byte[] bm = readLikeAndroid(big);
        check(parseUri(bm).equals(maxUrl), "a link of exactly " + Type4Tag.MAX_URL_CHARS + " chars still reads back intact");

        System.out.println("\nALL " + checks + " CHECKS PASSED");
    }

    static void expectReject(String url, String what) {
        try {
            Type4Tag.buildUriRecord(url);
        } catch (IllegalArgumentException e) {
            check(true, "rejects " + what);
            return;
        }
        check(false, "rejects " + what);
    }
}
