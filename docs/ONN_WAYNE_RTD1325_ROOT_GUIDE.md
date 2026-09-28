# onn 4K Streaming Stick (2026) — wayne / RTD1325
## Consolidated Guide: Block OTA → Unlock Bootloader → Root with Magisk

Compiled from the three XDA guides by **almyz125**, with corrections and
additions from **BigEmpty** and **Skel40**, plus setup notes for an
AdGuard Home / GL-MT6000 network.

---

## READ THIS FIRST — the one mistake you cannot undo

The stick ships on build `UKRB.251217.001.A1`. The first time it touches the
internet during the setup wizard it will try to install OTA
`UKRB.260113.027.A1`. **That update replaces the bootcode partition with a
bootloader whose fastboot unlock command is stripped out.** After it installs,
`fastboot flashing unlock` and every other bootloader fastboot command fail
with `unknown reason`, and there is no known way back.

There is no "skip" button in the wizard. The only defense is blocking Google's
OTA domains at the network *before the stick is ever powered on*.

Order of operations is therefore fixed and non-negotiable:

```
Block domains  →  Setup  →  Unlock  →  (wipe, setup again)  →  Root
```

Devices unlocked *before* an update stay unlocked across updates. Devices still
locked when they update are permanently locked. Get the unlock done early.

---

# PHASE 0 — Preflight

Do all of this before the stick comes out of the box. Nothing here touches the
device.

## 0.1 Hardware checklist

- [ ] onn 4K Streaming Stick (2026), still sealed / never powered on
- [ ] **USB-C to USB-C cable** — A-to-C will not initiate the boot logo (Skel40)
- [ ] A pin, needle, or SIM ejector tool for the hidden side button
- [ ] TV with HDMI, PC with a USB-C port
- [ ] Optional but useful: USB-C hub + keyboard/mouse (Android Recovery accepts both)

## 0.2 PC tools

Install current Android platform-tools. `adb pair` requires v33 or newer —
older builds will fail confusingly.

```bash
adb version        # expect 1.0.41 / platform-tools 34+
fastboot --version
```

## 0.3 Files to download and verify

Grab these from the XDA thread attachments **now**, before you start. They
require an XDA login and you do not want to discover a broken download
mid-flash.

| File | Purpose | SHA256 |
|---|---|---|
| `init_boot_…_UKRB.251217.001.A1_14612037.img` | Stock init_boot for the shipping build — patch target *and* your rollback | `bbdc73fb81ca051ba4c87c0949dffb77997231f69d21a1f758246b12d462f881` |
| `init_boot_…_UKRB.260113.027.A1_14997501.img` | Only if your unit somehow ships already updated | `49278be4ee8467becc7bf57bc6f799447c488812d1ec0db94044430464a98118` |
| `bootcode_…_UKRB.251217.001.A1_14612037.img` | **Insurance.** Factory bootcode — restores fastboot if an OTA ever kills it | `e9646ad2e8dd6e7126e159648d654c8dec5e674268a8e0208427f89c7458b3d1` |
| `Magisk-v30.7.apk` | Root. From the official Magisk releases page | (verify against the release page) |

Verify every one before use:

```bash
sha256sum <file>.img
```

**If a hash does not match, stop.** Re-download. Never flash an unverified image.

> Note: you may see `UKRB.251205.001/14546191` referenced. It only ever exists
> as partial leftovers in the inactive slot; no retail stick runs it. Ignore it.

## 0.4 Network — AdGuard Home configuration

The stick must resolve DNS through AdGuard Home, which means it must be on the
**Flint 2's Wi-Fi**, not upstream (Orbi/BGW320).

### Add the block rules

AGH → **Filters → Custom filtering rules**. Add all six:

```
||gvt1.com^
||gvt2.com^
||update.googleapis.com^
||dl.google.com^
||android.googleapis.com^
||play.googleapis.com^
```

The `||…^` form covers subdomains, which matters — the OTA package itself is
served from `android.googleapis.com/packages/ota-api/…` (URL logged by
BigEmpty), and `gvt1`/`gvt2` are used almost entirely via subdomains.

**Learn the toggle now, before you need it under time pressure.** During the
wizard you will unblock exactly two of these for a few minutes. In AGH, comment
them out with `#` and click Apply:

```
# ||android.googleapis.com^
# ||play.googleapis.com^
```

Practice the comment → Apply → uncomment → Apply cycle once so it's muscle
memory.

### Force DNS through AGH

Google TV devices sometimes ignore DHCP-supplied DNS. On the GL-MT6000:

- Enable the option that overrides/forces DNS for all clients (redirects
  outbound port 53 to the router)
- Block outbound **TCP/UDP 853** (DoT) so the stick can't tunnel around you

### Verify before the stick exists

From a laptop on the same Wi-Fi:

```bash
dig +short update.googleapis.com
dig +short android.googleapis.com
```

Both should return `0.0.0.0` (or NXDOMAIN, depending on your AGH blocking
mode) — not a real Google IP. **If they return real IPs, the block is not
working and you must not power on the stick.**

### If the wizard doesn't behave (fallback)

The guide author used UniFi, which serves an HTML block page, and theorized
that returning *something* rather than nothing is what makes the update fail
gracefully instead of hanging. The older onn YOC guides blackholed to `0.0.0.0`
with dnsmasq and worked fine, so AGH's null-IP response should be sufficient.
If you get stuck at a hang rather than a clean failure, set AGH's blocking mode
to **Custom IP** pointed at a local web server serving any small HTML file.

## 0.5 Your live instrument

Keep the **AGH Query Log** open in a browser during the entire wizard. This is
the advantage the original author didn't have: you can watch in real time
whether the stick is hitting the blocked domains and whether they're actually
being blocked. If you see a green (allowed) hit on `android.googleapis.com`
while it should be blocked, you know immediately — don't wait for the TV to
tell you.

---

# PHASE 1 — Setup Without the Forced OTA

### GATE — do not proceed until all are true

- [ ] All six domains blocked in AGH and confirmed via `dig`
- [ ] Stick will join the **Flint 2** SSID
- [ ] AGH Query Log open
- [ ] You have read the Break Glass procedure (Appendix A) — you may need it fast

## 1.1 Boot and start the wizard

1. Plug in the stick. Pair the remote when prompted.
2. Select language and region.
3. Choose **"Manual setup (with TV remote)"** — not the phone/Google Home path.
   The Google Home route is a known dead end on these sticks.
4. Select the Flint 2 Wi-Fi network and enter the password.

## 1.2 Let the update check fail (this is the goal)

You will see, in order:

1. "Connecting…"
2. "Connected"
3. A white spinner — **this spins for a long time. Do not touch anything.**
4. Eventually: **"`<Network Name>` cannot reach internet. Check your connection
   and try again."**

That message is exactly what you want. It means the update check failed.

> **If instead you see anything indicating an update has begun downloading:
> pull the power immediately** and go to Appendix A. Do not let it finish.

## 1.3 The two-domain window

**Before touching the TV**, go to AGH and comment out **only** these two:

```
# ||android.googleapis.com^
# ||play.googleapis.com^
```

Apply. Leave `gvt1.com`, `gvt2.com`, `update.googleapis.com`, and
`dl.google.com` **blocked**. Those four are what actually serve the update
payload; unblocking any of them here is how people get bricked.

Now on the TV:

5. Click **"Go to network setup"**
6. Select the same network — the password is saved, just continue through
7. "Connecting" → "Connected" → spinner (shorter this time)
8. It will attempt an update; the message **flashes a few times and fails.**
   You want this failure.
9. It falls through to **Google Account sign-in**. Authenticate.
10. Decline the data-collection and location prompts (or accept — your call)
11. "Setting up your Google TV" progress bar → Google TV Launcher

## 1.4 Re-block immediately

The moment you're on the launcher, go back to AGH and **uncomment both rules**.
Apply. All six blocked again. Confirm with `dig`.

### GATE — verify before moving on

Get ADB up (Phase 2.1–2.3 below), then:

```bash
adb shell getprop ro.build.fingerprint
```

**Must contain `UKRB.251217.001.A1` / `14612037`.** If it shows
`UKRB.260113.027.A1`, the update installed and unlock is gone — see Appendix C.

> **Note:** with all six blocked, the Play Store cannot download apps. That's
> fine — sideload via `adb install`. If you want the Store back *after* rooting,
> drop `play.googleapis.com` and probably `dl.google.com`, but never
> `update.googleapis.com` or the `gvt` pair.

---

# PHASE 2 — Unlock the Bootloader

**This wipes the device.** Do it before installing or configuring anything.
Expect to redo Phase 1 afterward.

Connection model, which trips people up:
- **ADB: wireless only.** The stick does not present ADB over USB when booted
  to Android.
- **Fastboot: USB-C only.** Have the stick plugged into the PC before rebooting
  to the bootloader.

## 2.1 Enable Developer Options

Settings → System → About → **Android TV OS build** → click **7 times** until
"You are now a developer!"

## 2.2 Enable the three toggles

Settings → System → Developer options:

- [ ] **OEM unlocking** — accept the prompt. Without this, `flashing unlock` is
      refused.
- [ ] **USB debugging** — required even though ADB doesn't run over USB
- [ ] **Wireless debugging** — accept the prompt

## 2.3 Pair ADB over Wi-Fi

In **Wireless debugging**, choose "Pair device with pairing code."

⚠️ **The pairing port and the connect port are different numbers.** The pairing
port is on the pairing dialog. The connect port is on the main Wireless
debugging screen. This is the single most common failure point.

```bash
adb pair <ip>:<PAIRING_port>     # enter the 6-digit code
adb connect <ip>:<CONNECT_port>
adb devices                      # should list "device", not "offline"
```

Your PC must be on the same subnet as the stick — i.e. also on the Flint 2
network. **Note:** the connect port changes on every reboot, so re-check it
after each restart.

If ADB shows `offline`: `adb kill-server && adb start-server`

## 2.4 Unlock

Plug the stick into the PC with the **C-to-C cable** first.

```bash
adb reboot bootloader
# wait a few seconds — the fastboot screen is BLACK, that is normal
fastboot devices          # should list WUSAxxxxxxxxxx  fastboot
fastboot flashing unlock
fastboot reboot
```

Expected:

```
(bootloader) flashing unlock partitions
OKAY [  1.850s]
```

If instead everything returns `FAILED (remote: 'unknown reason')` — including
`fastboot reboot` — the device is already updated and locked out. See
Appendix C. (Note that when fastboot is dead you can't even reboot from it;
pull power to escape.)

## 2.5 Set up again and verify

The unlock wiped the device. It boots back into the setup wizard.

**Run Phase 1 again in full** — same six-domain block, same two-domain window.
The domains never changed, so this pass is faster.

Then re-enable Developer Options and Wireless debugging (the wipe turned them
off), re-pair ADB, and verify:

```bash
adb shell getprop ro.boot.verifiedbootstate
```

- `orange` = **unlocked** ✅
- `green` = still locked ❌ — do not continue

---

# PHASE 3 — Root with Magisk

Root goes through **fastbootd** (Android's userspace fastboot), not the
bootloader. This is why root still works even on units whose bootloader
fastboot is dead. Unlock is the gate; the update is not.

### GATE

- [ ] `ro.boot.verifiedbootstate` returns `orange`
- [ ] `ro.build.fingerprint` matches the init_boot you downloaded
- [ ] Stock init_boot SHA256 verified
- [ ] You still have the stock init_boot saved as your rollback

## 3.1 Install the Magisk app

```bash
adb connect <ip>:<port>
adb install Magisk-v30.7.apk
```

## 3.2 Extract the patch kit — 32-bit!

**This stick is armeabi-v7a (32-bit).** Pulling arm64 binaries is a silent
failure mode — extract the right ones.

```bash
MAGISK=Magisk-v30.7.apk
INIT_BOOT=<your verified stock init_boot .img>

mkdir -p mp && cd mp
unzip -o -q "../$MAGISK" 'lib/armeabi-v7a/*' 'assets/*' -d .
cp lib/armeabi-v7a/libmagiskboot.so magiskboot
cp lib/armeabi-v7a/libmagiskinit.so magiskinit
cp lib/armeabi-v7a/libmagisk.so     magisk
cp lib/armeabi-v7a/libinit-ld.so    init-ld
cp assets/boot_patch.sh assets/util_functions.sh assets/stub.apk .
cd ..
```

## 3.3 Push and patch on-device

```bash
adb shell 'rm -rf /data/local/tmp/mp; mkdir -p /data/local/tmp/mp'
adb push mp/magiskboot mp/magiskinit mp/magisk mp/init-ld \
         mp/boot_patch.sh mp/util_functions.sh mp/stub.apk /data/local/tmp/mp/
adb push "$INIT_BOOT" /sdcard/Download/init_boot.img

adb shell 'cd /data/local/tmp/mp && chmod 755 magiskboot magiskinit magisk init-ld boot_patch.sh && sh boot_patch.sh /sdcard/Download/init_boot.img'
```

Look for these lines in the output — they confirm a clean patch:

```
- Stock boot image detected
- Patching ramdisk
- Pre-init storage partition: metadata
- Repacking boot image
Repack to boot image: [new-boot.img]
```

If it says anything other than **"Stock boot image detected"** (e.g. "Magisk
patched boot image detected"), you patched the wrong file. Start over with a
fresh stock image.

Pull it back:

```bash
adb pull /data/local/tmp/mp/new-boot.img init_boot-magisk.img
```

> Don't try to patch via the Magisk app's "Select and Patch a File" — the file
> picker is unusable with a TV remote, and Skel40 reports the Files app doesn't
> register with it at all ("You don't have an app that can do this").

## 3.4 Flash via fastbootd

⚠️ **`adb reboot fastboot`, NOT `adb reboot bootloader`.** These are different
modes. Bootloader fastboot cannot flash this correctly and, on updated units, is
dead entirely.

```bash
adb reboot fastboot            # userspace fastbootd
fastboot devices               # confirm it shows up
fastboot getvar current-slot   # returns a or b — READ THIS CAREFULLY
```

Flash **only the active slot** reported above:

```bash
fastboot flash init_boot_a init_boot-magisk.img    # if current-slot is a
# OR
fastboot flash init_boot_b init_boot-magisk.img    # if current-slot is b

fastboot reboot
```

> **Why the slot matters:** this is a Virtual A/B device. The inactive slot is
> *not* a bootable copy of your build, so a wrong-slot flash isn't fixed by
> switching slots. Flashing the inactive slot is also what preceded the
> permanent brick Skel40 reported on the updated build. Read `current-slot`,
> flash that one, nothing else.

## 3.5 Verify root

`su` is not on PATH right after boot — Magisk lives under `/debug_ramdisk`.

```bash
adb connect <ip>:<port>          # port changed on reboot
adb shell '/debug_ramdisk/magisk -V'      # e.g. 30700
adb shell '/debug_ramdisk/su -c id'       # uid=0(root) … context=u:r:magisk:s0
```

⚠️ The `su` call pops a **grant prompt on the TV with a countdown**. Have the
remote in hand and hit **GRANT** before it expires, or the call fails and you'll
think root is broken.

`magisk -V` printing a version already means you are rooted.

## 3.6 Finish Magisk setup

Magisk has **no launcher icon**. Open it via:

**Settings → Apps → See all apps → Magisk → Open** → click **OK** when prompted.
It may reboot once. Done.

---

# PHASE 4 — Post-root

- [ ] Confirm all six domains still blocked in AGH; spot-check the Query Log
- [ ] Confirm `ro.build.fingerprint` still shows `UKRB.251217.001.A1`
- [ ] Back up your patched `init_boot-magisk.img` alongside the stock one
- [ ] Keep the factory bootcode `e9646ad2…` archived — Appendix B is only
      possible if you have it

Optional next steps now available: systemless CA trust for MITM traffic capture,
system-app debloat, and per-app modules.

---

# APPENDIX A — Break Glass: Factory Reset via Hidden Button

Use when an update starts downloading, or when the wizard refuses to proceed.
Once the device knows about a pending update it will not let you sign in until
it installs — a factory reset clears that knowledge so you can retry.

1. **Unplug power.** Leave HDMI connected.
2. With a pin/SIM tool, press and **hold** the hidden button in the small hole
   on the side of the stick.
3. **While still holding**, plug power back in. Keep holding.
4. Hold through the **onn** splash screen. **Release the instant the onn logo
   disappears** — during the brief black-screen moment. This takes a few tries;
   that's normal.
5. In recovery, using the same hidden button:
   - **Navigate** = quick presses
   - **Select** = hold until the item turns **green** (it's blue when merely
     highlighted)
6. Navigate to **Wipe data/factory reset** → hold until green
7. Navigate to **Factory data reset** → hold until green
8. It wipes, returns to the main menu, log output at the bottom
9. Highlight **reboot** → hold until green

Recovery also accepts a **USB keyboard and mouse** via a USB-C hub, which is
far less fiddly than the pin. Before retrying, re-confirm your domain blocks
are actually live.

---

# APPENDIX B — Restore the Bootloader's Fastboot

**Only for a stick that was unlocked and then let an OTA install.** You do not
need this to be rooted — everything in Phase 3 uses fastbootd. This just brings
back the bootloader's own fastboot.

**Requires root already.** fastbootd cannot flash `bootcode`, so this is a `dd`
from root.

Verified in both directions: BigEmpty flashed the OTA bootcode onto a
non-updated device and killed fastboot, then restored the factory bootcode and
fastboot came back; almyz125 confirmed the inverse on an updated device.

```bash
sha256sum <the .img>    # MUST equal e9646ad2e8dd6e7126e159648d654c8dec5e674268a8e0208427f89c7458b3d1
BOOTCODE=<the factory e9646ad2 .img>

adb push "$BOOTCODE" /data/local/tmp/bootcode.img
adb shell 'su -c "dd if=/data/local/tmp/bootcode.img of=/dev/block/mmcblk0p2 && dd if=/data/local/tmp/bootcode.img of=/dev/block/mmcblk0p3 && sync"'
adb reboot
```

`bootcode_a` = `mmcblk0p2`, `bootcode_b` = `mmcblk0p3`. Both are written
because the OTA overwrote both.

Check:

```bash
adb reboot bootloader
fastboot getvar unlocked      # 'unlocked: yes' instead of 'unknown reason'
```

🛑 **Writing bootcode overwrites the bootloader. The wrong file or the wrong
device permanently bricks it.** Only these exact images, only on wayne/RTD1325,
only when already unlocked and rooted. Tested only on `UKRB.251217.001.A1` and
`UKRB.260113.027.A1`. **Anything newer is uncharted** — a newer OTA ships its
own bootcode, and writing the older one under a newer system is untested.

---

# APPENDIX C — Diagnosing a Locked-Out Device

```bash
fastboot devices          # lists WUSAxxxxxxxxxx  fastboot
fastboot flashing unlock  # FAILED (remote: 'unknown reason')
fastboot getvar unlocked  # FAILED (remote: 'unknown reason')
fastboot reboot           # FAILED (remote: 'unknown reason')
```

If **everything** fails with `unknown reason` and the device was never
unlocked, the OTA disabled unlock and there is no known way back. Root is off
the table. (You'll need to pull power to exit fastboot, since even `reboot`
fails.)

If the device **was unlocked before** the update, you're fine — unlock persists
across updates. Root via fastbootd (Phase 3) still works, and Appendix B can
restore bootloader fastboot afterward.

---

# APPENDIX D — Quick Reference

## Build IDs

| Build | Status |
|---|---|
| `UKRB.251205.001/14546191` | Never shipped whole; partial leftovers in inactive slot. Ignore. |
| `UKRB.251217.001.A1/14612037` | **Shipping build. Unlockable and rootable — this is what you want.** |
| `UKRB.260113.027.A1/14997501` | First OTA. Kills bootloader fastboot. Rootable only if unlocked beforehand. |
| Anything newer | Uncharted. |

## The six domains

```
gvt1.com  gvt2.com  update.googleapis.com
dl.google.com  android.googleapis.com  play.googleapis.com
```

Unblock **only** `android.googleapis.com` + `play.googleapis.com`, **only** in
the Phase 1.3 window, **always** re-block at the launcher.

## Two easy mistakes that look identical to success

1. `adb reboot bootloader` instead of `adb reboot fastboot` in Phase 3
2. Pairing port used as the connect port in Phase 2.3

## Command cheat sheet

```bash
# Identity / state
adb shell getprop ro.build.fingerprint
adb shell getprop ro.boot.verifiedbootstate      # orange = unlocked

# Connect
adb pair <ip>:<pairing_port>
adb connect <ip>:<connect_port>
adb kill-server && adb start-server              # if offline

# Modes
adb reboot bootloader     # bootloader fastboot — unlock only
adb reboot fastboot       # fastbootd — flashing only

# Root check
adb shell '/debug_ramdisk/magisk -V'
adb shell '/debug_ramdisk/su -c id'
```

---

**Credits:** almyz125 (all three original guides), BigEmpty (identified that the
OTA bootcode is what breaks fastboot; logged the OTA package URL; clarified the
251205 build), Skel40 (C-to-C cable requirement, recovery keyboard/mouse
support, fastbootd brick warning).
