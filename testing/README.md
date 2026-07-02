# 🧪 Morphe Patch Testing Environment

A local harness for testing Morphe CLI APK patches on an **Onn TV** (or any
Android TV / Google TV box) over ADB **before** you push changes to the repo.

The loop it automates:

```
edit patch → build bundle → patch an APK → install on the TV → watch logs
```

Everything device-specific and every binary artifact stays **local and
gitignored**. Only the scripts, config templates, and this README are committed,
so you can copy the whole `testing/` folder into any future patch project.

---

## 📁 Layout

```
testing/
├── README.md              ← you are here
├── .gitignore             ← keeps APKs, tools, keys, device.env out of git
├── config/
│   ├── device.env.example ← copy → device.env, set your TV's IP
│   ├── device.env         ← (gitignored) your TV details
│   └── apps.conf          ← app-key → android package name registry
├── scripts/
│   ├── lib.sh             ← shared helpers (sourced by the others)
│   ├── setup.sh           ← download Morphe CLI + APKEditor, check host tools
│   ├── build.sh           ← build the patch bundle from the working tree
│   ├── patch.sh           ← patch an APK/APKM with the local bundle
│   ├── deploy.sh          ← install the patched APK on the TV + launch it
│   ├── logs.sh            ← stream filtered logcat from the TV
│   └── test.sh            ← build → patch → deploy → logs, all in one
├── apks/                  ← (gitignored) drop your .apkm downloads here
├── out/                   ← (gitignored) merged + patched APKs, local bundle
├── tools/                 ← (gitignored) morphe-cli.jar, APKEditor.jar
└── keystore/              ← (gitignored) test signing keystore
```

---

## ✅ Prerequisites (on your computer, one time)

- **JDK 17+** — the patch build and Morphe CLI both need it (`java -version`).
- **Android platform-tools** (`adb`) on your `PATH`.
- **`curl`** — used by `setup.sh` to fetch the jars.

### On the Onn TV (one time)

1. **Settings → Device Preferences → About** → click **Build** 7 times to unlock
   Developer options.
2. **Settings → Device Preferences → Developer options** → enable
   **Network debugging** (and **USB debugging** if you'll use a cable).
3. Note the TV's IP under **Settings → Network & Internet**. A DHCP reservation
   on your router keeps it stable.

---

## 🚀 Quick start

```bash
cd testing

# 1. Download the CLI + APKEditor and verify java/adb are present
./scripts/setup.sh

# 2. Tell the scripts about your TV
cp config/device.env.example config/device.env
$EDITOR config/device.env        # set DEVICE_IP

# 3. Download the app's .apkm from APKMirror into apks/
#    (e.g. apks/disneyplus-26.9.2.apkm)

# 4. Run the whole loop
./scripts/test.sh disneyplus apks/disneyplus-26.9.2.apkm
```

The first ADB connection makes the TV show an **"Allow debugging?"** dialog —
accept it (and check "always allow") on the TV.

---

## 🔧 Scripts, individually

Run any of these from the `testing/` directory.

| Script | What it does |
|--------|--------------|
| `./scripts/setup.sh` | Downloads the latest **Morphe CLI** and **APKEditor** jars into `tools/`, checks that `java` and `adb` work. `--check` verifies only, downloads nothing. |
| `./scripts/build.sh` | Runs `./gradlew :patches:buildAndroid` at the repo root and stages the resulting `.mpp` as `out/patches-local.mpp`. Then lists the patches inside it so you can confirm your change landed. `--clean` does a clean build (matches CI). |
| `./scripts/patch.sh <app> <input>` | Merges a split `.apkm`/`.apks`/`.xapk` into one APK (via APKEditor), then patches it with the local bundle → `out/<app>-patched.apk`. Extra args after the input pass straight to the CLI (e.g. `-e "Clone Prime Video"`). |
| `./scripts/deploy.sh <app> [apk]` | Uninstalls the stock app (signatures differ), installs the patched APK over ADB, and launches it. |
| `./scripts/logs.sh <app> [regex]` | Clears the log buffer and tails logcat for that app's process. Add a regex to grep (e.g. `ad\|advert\|volley`). |
| `./scripts/test.sh <app> <input>` | build → patch → deploy → logs in sequence. |

`<app>` is a short key from `config/apps.conf` (`disneyplus`, `primevideo`,
`hbomax`, `peacock`, `tubi`, `vix`, `paramount`). Add new apps by appending a
`key|com.package.name|Display Name` line to that file.

---

## 🎯 Testing only specific patches

`patch.sh` forwards anything after the input APK to the Morphe CLI, so you can
scope a run to the patch you're working on:

```bash
# Enable only one patch by name (exclusive mode)
./scripts/patch.sh primevideo apks/primevideo.apkm --exclusive -e "Block Prime Video ads"

# See every patch (and its options) in the local bundle
java -jar tools/morphe-cli.jar list-patches --with-options out/patches-local.mpp
```

---

## 🩺 Troubleshooting

- **`device ... not reachable`** — re-run after confirming Network debugging is
  on and you accepted the RSA prompt on the TV. Try `adb connect <ip>:5555`
  manually. Some boxes reset the port; re-toggle Network debugging.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — the stock app is still installed
  with a different signature. `deploy.sh` uninstalls first, but a system-app
  variant may need `adb uninstall <package>` by hand.
- **`INSTALL_FAILED_NO_MATCHING_ABIS`** — you downloaded an APKM for the wrong
  CPU. Onn TV boxes are ARM; grab the `armeabi-v7a`/`arm64-v8a` variant.
- **App crashes immediately** — run `logs.sh <app>` right after launch and look
  for the stack trace; a `VerifyError` usually means a fingerprint in your patch
  matched the wrong method.
- **Build can't reach the Morphe registry** — the root build pulls from
  `maven.pkg.github.com/MorpheApp/registry`, which needs a GitHub token. Set
  `GITHUB_ACTOR` and `GITHUB_TOKEN`, or add `gpr.user`/`gpr.key` to
  `~/.gradle/gradle.properties` (never commit those).

---

## 🔒 What is and isn't committed

**Committed:** the scripts, `config/apps.conf`, `config/device.env.example`,
this README, and empty `apks/ out/ tools/ keystore/` folders (via `.gitkeep`).

**Never committed** (see `.gitignore`): downloaded jars, any `.apk`/`.apkm`,
your patched output, the signing keystore, and `config/device.env`. APKs are
copyrighted and keystores/device IPs are yours alone — keep them off GitHub.
