package ke.co.payswiftly.tap;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * An emulated NFC Forum Type 4 Tag (NDEF Tag Application, mapping v2.0) that
 * carries a single NDEF "URI" record: the PaySwiftly payment link.
 *
 * This is deliberately pure Java with no Android imports, so the exact byte
 * sequence a reading phone sees can be tested on a laptop
 * (see tools/Type4TagSelfTest.java).
 *
 * A reading phone drives this with ISO 7816-4 commands:
 *   1. SELECT the NDEF application   (AID D2760000850101)
 *   2. SELECT the Capability Container file (E103), READ it
 *   3. SELECT the NDEF file (E104), READ the 2-byte length, then the message
 * The phone then treats what it read as an ordinary NFC tag, and opens the URL.
 */
public final class Type4Tag {

    /** Receives progress so the UI can show that a tap really happened. */
    public interface Listener {
        void onEvent(String event);

        void onNdefReadComplete();
    }

    public static final byte[] AID = {
        (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01
    };

    /** Longest link we accept; keeps the whole record in one short NDEF record. */
    public static final int MAX_URL_CHARS = 200;

    /** Largest NDEF file (NLEN + message) advertised to readers, in bytes. */
    static final int MAX_NDEF_FILE = 0xFF;
    /** Largest response / command payload we advertise, in bytes. */
    static final int MLE = 0xFF;
    static final int MLC = 0xFF;

    static final int FILE_CC = 0xE103;
    static final int FILE_NDEF = 0xE104;

    // Status words (ISO 7816-4)
    public static final byte[] SW_OK = {(byte) 0x90, 0x00};
    public static final byte[] SW_WRONG_LENGTH = {0x67, 0x00};
    public static final byte[] SW_CONDITIONS = {0x69, (byte) 0x85};
    public static final byte[] SW_NOT_FOUND = {0x6A, (byte) 0x82};
    public static final byte[] SW_WRONG_PARAMS = {0x6B, 0x00};
    public static final byte[] SW_INS_UNSUPPORTED = {0x6D, 0x00};
    public static final byte[] SW_CLA_UNSUPPORTED = {0x6E, 0x00};

    private static final int FILE_NONE = 0;

    private final byte[] cc;
    private final byte[] ndefFile; // NLEN (2 bytes, big-endian) + NDEF message
    private final Listener listener;

    private boolean appSelected;
    private int selectedFile = FILE_NONE;

    public Type4Tag(String url, Listener listener) {
        byte[] message = buildUriRecord(url);
        this.ndefFile = new byte[2 + message.length];
        this.ndefFile[0] = (byte) (message.length >> 8);
        this.ndefFile[1] = (byte) message.length;
        System.arraycopy(message, 0, this.ndefFile, 2, message.length);
        this.cc = new byte[] {
            0x00, 0x0F,                        // CCLEN = 15
            0x20,                              // mapping version 2.0
            (byte) (MLE >> 8), (byte) MLE,     // MLe: max bytes per READ response
            (byte) (MLC >> 8), (byte) MLC,     // MLc: max bytes per command
            0x04, 0x06,                        // NDEF File Control TLV (tag 04, len 06)
            (byte) (FILE_NDEF >> 8), (byte) FILE_NDEF,
            (byte) (MAX_NDEF_FILE >> 8), (byte) MAX_NDEF_FILE,
            0x00,                              // read access: open
            (byte) 0xFF                        // write access: none (read-only)
        };
        this.listener = listener;
    }

    /**
     * Builds a single short NDEF URI record (well-known type "U") for an
     * https:// or http:// link, using the standard one-byte prefix code.
     */
    public static byte[] buildUriRecord(String url) {
        if (url == null) throw new IllegalArgumentException("Link is empty");
        String u = url.trim();
        if (u.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("Link is longer than " + MAX_URL_CHARS + " characters");
        }
        int prefixCode;
        String rest;
        if (u.startsWith("https://")) {
            prefixCode = 0x04;
            rest = u.substring("https://".length());
        } else if (u.startsWith("http://")) {
            prefixCode = 0x03;
            rest = u.substring("http://".length());
        } else {
            throw new IllegalArgumentException("Link must start with https://");
        }
        if (rest.isEmpty()) throw new IllegalArgumentException("Link has no address after https://");
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch <= ' ' || ch > '~') {
                throw new IllegalArgumentException("Link contains a space or unsupported character");
            }
        }
        byte[] restBytes = rest.getBytes(Charset.forName("US-ASCII"));
        int payloadLen = 1 + restBytes.length; // prefix code + remainder
        byte[] record = new byte[4 + payloadLen];
        record[0] = (byte) 0xD1;               // MB=1 ME=1 CF=0 SR=1 IL=0 TNF=001 (well-known)
        record[1] = 0x01;                      // type length
        record[2] = (byte) payloadLen;         // payload length (short record, <= 255)
        record[3] = 0x55;                      // type 'U' (URI)
        record[4] = (byte) prefixCode;
        System.arraycopy(restBytes, 0, record, 5, restBytes.length);
        return record;
    }

    /** The reader moved away or the link dropped: forget what was selected. */
    public void reset() {
        appSelected = false;
        selectedFile = FILE_NONE;
    }

    /** Handles one command APDU from the reader and returns the response APDU. */
    public byte[] process(byte[] c) {
        if (c == null || c.length < 4) return fail(SW_WRONG_LENGTH, "malformed command");
        int cla = c[0] & 0xFF;
        int ins = c[1] & 0xFF;
        int p1 = c[2] & 0xFF;
        int p2 = c[3] & 0xFF;
        if ((cla & 0xFC) != 0x00) return fail(SW_CLA_UNSUPPORTED, "unsupported class " + hex(cla));
        switch (ins) {
            case 0xA4:
                return select(c, p1, p2);
            case 0xB0:
                return readBinary(c, p1, p2);
            case 0xD6: // UPDATE BINARY
            case 0xD0: // WRITE BINARY
                return fail(SW_CONDITIONS, "write refused (read-only tag)");
            default:
                return fail(SW_INS_UNSUPPORTED, "unsupported command " + hex(ins));
        }
    }

    private byte[] select(byte[] c, int p1, int p2) {
        if (c.length < 5) return fail(SW_WRONG_LENGTH, "SELECT without data");
        int lc = c[4] & 0xFF;
        if (c.length < 5 + lc) return fail(SW_WRONG_LENGTH, "SELECT truncated");
        byte[] data = Arrays.copyOfRange(c, 5, 5 + lc);

        if (p1 == 0x04) { // by DF name (application ID)
            if (Arrays.equals(data, AID)) {
                appSelected = true;
                selectedFile = FILE_NONE;
                return ok("SELECT app");
            }
            return fail(SW_NOT_FOUND, "SELECT unknown app");
        }
        if (p1 == 0x00) { // by file ID
            if (!appSelected) return fail(SW_NOT_FOUND, "SELECT file before app");
            if (data.length != 2) return fail(SW_WRONG_LENGTH, "file id must be 2 bytes");
            int id = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            if (id == FILE_CC) {
                selectedFile = FILE_CC;
                return ok("SELECT capability file");
            }
            if (id == FILE_NDEF) {
                selectedFile = FILE_NDEF;
                return ok("SELECT NDEF file");
            }
            return fail(SW_NOT_FOUND, "SELECT unknown file " + hex(id));
        }
        return fail(SW_WRONG_PARAMS, "SELECT mode " + hex(p1) + " unsupported");
    }

    private byte[] readBinary(byte[] c, int p1, int p2) {
        if (selectedFile == FILE_NONE) return fail(SW_CONDITIONS, "READ with no file selected");
        if ((p1 & 0x80) != 0) return fail(SW_WRONG_PARAMS, "READ by short file id unsupported");
        if (c.length < 5) return fail(SW_WRONG_LENGTH, "READ without length");
        byte[] file = selectedFile == FILE_CC ? cc : ndefFile;
        int offset = ((p1 & 0x7F) << 8) | p2;
        int le = c[4] & 0xFF;
        if (le == 0) le = 256;
        if (offset > file.length) return fail(SW_WRONG_PARAMS, "READ offset past end");
        int n = Math.min(Math.min(le, MLE), file.length - offset);
        byte[] out = new byte[n + 2];
        System.arraycopy(file, offset, out, 0, n);
        out[n] = SW_OK[0];
        out[n + 1] = SW_OK[1];
        boolean isNdef = selectedFile == FILE_NDEF;
        say((isNdef ? "READ NDEF " : "READ capability ") + offset + "+" + n);
        if (isNdef && n > 0 && offset + n >= file.length && listener != null) {
            listener.onNdefReadComplete();
        }
        return out;
    }

    private byte[] ok(String event) {
        say(event);
        return SW_OK.clone();
    }

    private byte[] fail(byte[] sw, String event) {
        say("refused: " + event);
        return sw.clone();
    }

    private void say(String event) {
        if (listener != null) listener.onEvent(event);
    }

    private static String hex(int v) {
        return String.format("0x%02X", v);
    }
}
