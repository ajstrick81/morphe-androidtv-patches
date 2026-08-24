# onn 4K Streaming Stick (2026) — wayne / RTD1325

USB-driven toolkit for the block-OTA → unlock → root process, wrapped around
[`docs/ONN_WAYNE_RTD1325_ROOT_GUIDE.md`](../../docs/ONN_WAYNE_RTD1325_ROOT_GUIDE.md).
The guide is the source of truth; this directory is the executable half of it.

Everything runs from the PC with the stick on the other end of a USB-C cable.

---

## What "over USB" actually means on this stick

This is the constraint the whole toolkit is shaped around, and it is the thing
most people get wrong:

| Transport | Carries | When |
|---|---|---|
| **USB-C** | `fastboot` (bootloader) | `flashing unlock` — and nothing else |
| **USB-C** | `fastbootd` (userspace) | flashing `init_boot` — and nothing else |
| **Wi-Fi** | `adb` | every read, the on-device Magisk patch, APK installs |

Booted to Android, the stick **does not present ADB over USB**. Fastboot, both
kinds, is **USB-only**. So the cable carries every irreversible write — which is
exactly where you want a wire rather than a wireless link that can drop
mid-flash — and Wi-Fi ADB carries the reversible parts.

The cable must be **USB-C to USB-C**. A-to-C does not enumerate on this stick.

`scripts/08-usb-adb.sh` is an experiment at pushing that wall back: the port
speaks fastboot in device mode, so the controller can clearly do it, and the
missing ADB is a gadget-config choice rather than a hardware fact. It may not
work. It is non-destructive and revertible either way.

---

## Layout

```
testing/onn-wayne/
├── onn.sh                     ← single entrypoint: ./onn.sh <command>
├── config/wayne.env.example   ← copy to wayne.env (gitignored)
├── images/
│   ├── manifest.tsv           ← SHA256 of every image the toolkit will accept
│   ├── fingerprints.tsv       ← build → matching init_boot
│   └── (your .img + Magisk APK — gitignored)
├── work/                      ← patched output, logs (gitignored)
└── scripts/
    ├── lib.sh                    shared helpers, transport model, gates
    ├── 00-preflight.sh           host tools, image hashes, OTA block check
    ├── 01-status.sh              read device state  [--json]
    ├── 02-adb-pair.sh            Wi-Fi pairing; rewrites ADB_PORT for you
    ├── 03-unlock.sh              USB: flashing unlock            (WIPES)
    ├── 04-patch-init-boot.sh     Magisk-patch init_boot on-device
    ├── 05-flash-root.sh          USB: flash patched init_boot via fastbootd
    ├── 06-verify-root.sh         confirm root
    ├── 07-install-apk.sh         install a Morphe-patched APK
    ├── 08-usb-adb.sh             experimental: ADB over the cable
    ├── 90-restore-bootcode.sh    Appendix B: restore bootloader fastboot
    └── 99-rollback-init-boot.sh  restore stock init_boot (removes root)
```

Images, the Magisk APK, patched output, and `wayne.env` are all gitignored.
Nothing binary or device-identifying goes into the repo.

---

## Run order

```bash
cp config/wayne.env.example config/wayne.env   # edit DEVICE_IP, MAGISK_APK
./onn.sh preflight --udev                      # BEFORE the stick is powered on
#   → Phase 1 of the guide is manual: the wizard + the two-domain window
./onn.sh pair                                  # after you reach the launcher
./onn.sh status                                # confirm the shipping build
./onn.sh unlock                                # USB. WIPES. Back to the wizard.
#   → redo the OTA-blocked wizard, then:
./onn.sh pair                                  # re-pair (the wipe turned it off)
./onn.sh patch                                 # Wi-Fi: patch init_boot
./onn.sh flash                                 # USB: fastbootd, active slot only
./onn.sh pair --reconnect                      # the port changed on reboot
./onn.sh verify                                # root
./onn.sh install ../out/pluto-patched.apk      # back to what this repo is for
```

**Run `./onn.sh status` between every step.** It reads the device instead of
assuming, and tells you which phase is actually next. `--json` makes it
parseable, which is how an agent drives this without guessing.

---

## What the scripts refuse to do

The failure modes on this device are permanent, so the gates are hard failures,
not warnings:

- **No unverified image is ever written.** Every `.img` is checked against
  `images/manifest.tsv` first. An unlisted hash aborts — there is no override
  flag, because an unrecognised bootcode is precisely the thing that bricks a
  stick.
- **`init_boot` is only flashed from fastbootd**, never bootloader fastboot.
  The script reads `is-userspace` and refuses on a mismatch. Getting this wrong
  looks like success right up until it doesn't.
- **Only the active slot is flashed**, read live from `fastboot getvar
  current-slot`. This is a Virtual A/B device: the inactive slot is not a
  bootable copy of your build, so a wrong-slot flash isn't fixed by switching
  slots — and it's what preceded the one permanent brick reported on the OTA
  build.
- **Nothing is flashed while `verifiedbootstate` is `green`.**
- **The build must be one of the two anyone has tested.** Newer builds ship
  their own bootcode; the toolkit stops rather than pretend it knows.
- **Patched-vs-stock is checked both ways.** `flash` refuses a stock image
  (that's what `rollback` is for), and `patch` aborts unless `boot_patch.sh`
  reports `Stock boot image detected` — the tell for double-patching.
- **Every destructive step needs a typed phrase**, not a y/n: `WIPE AND
  UNLOCK`, `FLASH init_boot_a`, `OVERWRITE BOOTLOADER`.

Steps that need a human at the TV or the router are printed as `[HANDS]` and
the script stops there. It never claims to have done something it can't do —
DNS toggles, remote presses, and the Magisk grant prompt are yours.

`WAYNE_ASSUME_YES=1` skips the pauses and `WAYNE_CONFIRM=<phrase>` pre-answers
one confirmation. They exist for re-runs of steps you have already done. Using
them to get through a step you haven't read is how people brick things.

---

## The part no script can do for you

Phase 1 of the guide — blocking the six OTA domains before the stick is ever
powered on — is a network change, and it is the one mistake that cannot be
undone. `./onn.sh preflight` verifies the block from this machine's resolver and
tells you what it cannot verify (that the *stick* resolves through the same
AdGuard Home instance). Keep the AGH query log open during the wizard.

```
gvt1.com   gvt2.com   update.googleapis.com
dl.google.com   android.googleapis.com   play.googleapis.com
```

Unblock **only** `android.googleapis.com` + `play.googleapis.com`, **only** in
the sign-in window, and re-block the moment you reach the launcher.
