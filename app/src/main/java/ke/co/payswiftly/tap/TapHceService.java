package ke.co.payswiftly.tap;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

/**
 * Android calls this whenever another phone's NFC reader talks to ours and
 * selects the NFC Forum NDEF application (see res/xml/apduservice.xml).
 * All protocol work is in Type4Tag; this class only connects it to the app.
 */
public class TapHceService extends HostApduService {

    private Type4Tag tag;
    private String tagUrl;

    private final Type4Tag.Listener listener = new Type4Tag.Listener() {
        @Override
        public void onEvent(String event) {
            TapState.contact(event);
        }

        @Override
        public void onNdefReadComplete() {
            TapState.tapCompleted();
        }
    };

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        String url = TapState.getUrl();
        if (url == null) {
            // Not sending: behave as if there were no tag here at all.
            TapState.contact("ignored (not sending)");
            return Type4Tag.SW_NOT_FOUND.clone();
        }
        try {
            if (tag == null || !url.equals(tagUrl)) {
                tag = new Type4Tag(url, listener);
                tagUrl = url;
            }
        } catch (IllegalArgumentException e) {
            TapState.contact("bad link: " + e.getMessage());
            return Type4Tag.SW_NOT_FOUND.clone();
        }
        return tag.process(commandApdu);
    }

    @Override
    public void onDeactivated(int reason) {
        // reason: DEACTIVATION_LINK_LOSS (0) or DEACTIVATION_DESELECTED (1).
        // Either way the next tap starts from SELECT again.
        if (tag != null) tag.reset();
        TapState.contact(reason == DEACTIVATION_LINK_LOSS ? "phones moved apart" : "reader finished");
    }
}
