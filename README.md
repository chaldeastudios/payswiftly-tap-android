# PaySwiftly Tap (test build)

A small Android app that lets one phone **send a PaySwiftly payment link to another phone by
touching them together** (NFC). The receiving phone opens the link in its browser and the customer
pays on the normal PaySwiftly page. No app is needed on the receiving phone.

This is a **test harness for the tap itself**. It proves the "touch phones, payment screen appears"
step on real devices before anything is built into the main app. It handles **no money, no logins and
no PINs**: it only sends a public web link.

Part of the PaySwiftly project: web app and docs live in
`chaldeastudios/pay-swiftly-landing` (see `docs/CONTACTLESS_NFC.md`, sections 4 and 5).

## How the full flow works

1. **Merchant phone** (this app, Android with NFC): creates a payment request in PaySwiftly, copies its
   link, and starts sending it.
2. **Customer phone** (any phone with NFC, Android or iPhone): touched against the merchant phone. It
   behaves exactly as if it had touched an NFC sticker, and opens the link.
3. The link is the normal `https://payswiftly.co.ke/r/<request id>` page. It shows the amount and
   receiver and sends the **M-Pesa prompt**; the customer enters their **M-Pesa PIN on their own
   phone**. (PaySwiftly can never see or replace that PIN.)
4. The merchant's PaySwiftly screen sees the payment succeed.

Steps 3 and 4 already exist in the web app. This project is step 1 and 2.

## Get the app onto a phone

### Option A: Android Studio (easiest if you have it)
1. Unzip. In Android Studio: **File > Open**, choose this folder. Let it sync (it downloads what it needs).
2. Plug in the Android phone (USB debugging on), press **Run**. Or **Build > Build APK(s)** and copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone.

### Option B: no Android Studio, let GitHub build it
1. Create a new empty repository on GitHub and upload this folder's contents (keep the
   `.github` folder).
2. Open the **Actions** tab > **Build debug APK** > **Run workflow**. About 3 minutes later, download
   the `payswiftly-tap-debug-apk` file at the bottom of the run.
3. Send `app-debug.apk` to the phone and open it. Android will ask to allow installing from this
   source: allow it. (It is a debug build signed with a throwaway key, for testing only.)

### Option C: command line
Install JDK 17 and the Android SDK (platform 34), then `./gradlew assembleDebug`.

## What you need to test

* **Phone 1 (sender):** Android with NFC *and* host card emulation. Most phones from the last several
  years have both; some budget models (for example the Samsung Galaxy A07) have no NFC chip at all.
* **Phone 2 (reader):** any phone with NFC. Android is the well-trodden case; see iPhone notes below.
* NFC switched on in both phones' settings. Both screens on and unlocked.

## Test plan

**Test 1: does the tap work at all? (do this first)**
1. On phone 1 open **PaySwiftly Tap**. It should say "NFC is ready on this phone."
2. Press **Use test link**, then **START SENDING**.
3. Touch the backs of the two phones together and hold still for about a second.
4. Expected: phone 2 vibrates or beeps and opens `payswiftly.co.ke` in its browser. Phone 1's screen
   shows **Taps completed: 1** and the time.

**Test 2: a real payment request**
1. Log in to PaySwiftly on any device, open **Receive**, enter an amount (try KES 10), and create the
   request. A link `https://payswiftly.co.ke/r/...` appears under the QR code. Copy it. (Requests
   **expire after 15 minutes**, so do the next steps straight away.)
2. In **PaySwiftly Tap** press **Paste**, then **START SENDING**. (Or use the phone's **Share**
   menu on the link and choose PaySwiftly Tap.)
3. Touch phone 2. Expected: the PaySwiftly pay page opens with the amount; the customer chooses to pay,
   gets the M-Pesa prompt and enters their PIN; the request flips to paid on the merchant side.

**Test 3: iPhone as the reading phone** (not yet verified by anyone on this project)
Same as Test 1 with an iPhone as phone 2. iPhones read NFC in the background from XS onward, with the
screen on and unlocked. If nothing happens, note the iPhone model and iOS version.

## If nothing happens

| What you see | What it means |
|---|---|
| "This phone has no NFC chip" | Phone 1 cannot send. Use another phone. |
| "NFC is switched off" | Turn NFC on in Settings (button provided). |
| "cannot emulate a tag" | Phone 1 has NFC but no host card emulation. Use another phone. |
| Sender shows "No phone has touched this one yet" | The phones are not lining up. NFC antennas are usually mid-back or by the camera. Slide slowly, remove thick or metal cases, keep both screens on. |
| Sender shows a contact but "Taps completed" stays 0 | Contact was too brief. Hold still for a full second. The "Last contact" line says which step it reached. |
| Reader opens nothing | Check the reader's NFC is on and its screen is unlocked. On Android, some phones need "Read NFC tags" enabled. |
| Another wallet/payment app appears on tap | Another app registered NFC emulation. Keep PaySwiftly Tap on screen (it takes priority while open). |

## What has and has not been checked

Checked (on a laptop, during development):
* The NFC tag protocol the app speaks passes **68 automated checks** that play the part of a reading
  phone: `bash tools/selftest.sh` (needs only a JDK).
* The Java compiles cleanly against the Android 14 framework API; the XML files are well-formed.

**Not checked:**
* **The Gradle / Android Gradle Plugin build**, and therefore the APK itself. The development
  environment could not reach Google's Android download servers. If the first build fails, send the
  error text. It will most likely be a version or setup detail.
* **Behaviour on real phones.** NFC cannot be tested without hardware. Test 1 above is the first real
  check, and I'd expect some phone-specific surprises.
* iPhone reading an Android-emulated tag.

## Design notes

* The app impersonates an **NFC Forum Type 4 Tag** holding one NDEF URI record, using the standard
  NDEF application ID `D2760000850101`. That is why any phone treats the touch like an NFC sticker.
* It deliberately **does not** pose as a payment card (EMV). Asking for a card application is refused.
  That keeps Paystack as the only processor and keeps Apple Pay / Google Wallet out of the flow.
* Only Android can send: iPhones cannot emulate a tag for third-party apps. They can still read.
* The sender must be Android; the app only works while its screen is open (it stops itself when you
  leave it, by design).
* The link is public, with no secret in it. Anyone who gets a link can open the same pay page.

## What comes next (not in this build)

1. Show the live PaySwiftly POS screen inside this app and send each new request's link
   automatically (no copy and paste). Needs a small hook added to the web app's POS screen.
2. Enforce the closed-testing allow-list (`profiles.is_internal_tester`) on the pay page, as the
   project's roadmap requires for any real-money tap. It is not enforced anywhere in the web app today.
3. Signed, single-use tags (NTAG 424 DNA) if a physical card or sticker is wanted.
