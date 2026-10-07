package ke.co.payswiftly.tap;

/**
 * What the screen and the NFC service share. The service runs in this app's
 * own process, so plain static fields are enough for a test build.
 */
final class TapState {
    private TapState() {}

    private static volatile String url;            // null = not sending
    private static volatile int taps;              // completed reads since "Start"
    private static volatile long lastTapAt;        // epoch ms of the last completed read
    private static volatile long lastContactAt;    // epoch ms of the last command from any reader
    private static volatile String lastEvent = "";

    static String getUrl() { return url; }

    static void start(String newUrl) {
        taps = 0;
        lastTapAt = 0;
        lastContactAt = 0;
        lastEvent = "";
        url = newUrl;
    }

    static void stop() { url = null; }

    static boolean isSending() { return url != null; }

    static void contact(String event) {
        lastEvent = event;
        lastContactAt = System.currentTimeMillis();
    }

    static void tapCompleted() {
        taps++;
        lastTapAt = System.currentTimeMillis();
    }

    static int getTaps() { return taps; }
    static long getLastTapAt() { return lastTapAt; }
    static long getLastContactAt() { return lastContactAt; }
    static String getLastEvent() { return lastEvent; }
}
