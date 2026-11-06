# PuddingRoot

One-tap **temporary root** for the **Xiaomi 17 Global (`pudding`)** on
**HyperOS 4 `4.0.1.0.XPCMIXM.D01`** (Android 17, kernel
`6.12.69-android16-6-g0a1dc0413ec7-ab16271006-4k`).

The app drives [Shizuku](https://github.com/RikkaApps/Shizuku) in shell mode,
runs the [CVE-2026-98260](https://github.com/Dream-Rainy/CVE-2026-98260)
local-privilege-escalation payload, and loads the installed **KernelSU /
KernelSU-Next / SukiSU** manager's kernel module via `ksud late-load`.

No PC, no unlocked bootloader, no partition changes. **Boot-only** — a reboot
removes it, and you re-run the app.

> Research / education / device-repair project. Only run it on devices you own.
> Read [`DISCLAIMER.md`](DISCLAIMER.md) first.

## Supported device

| item | value |
|---|---|
| device | Xiaomi 17 Global, codename `pudding` |
| firmware | HyperOS `4.0.1.0.XPCMIXM.D01` |
| kernel | `6.12.69-android16-6-g0a1dc0413ec7-ab16271006-4k` |

The app hard-refuses any other kernel. The exploit offsets are build-specific —
running it elsewhere can panic the kernel.

## Requirements

1. **Shizuku** (or a Shizuku-compatible fork) running in shell mode, with this
   app granted permission. `moe.shizuku.privileged.api` must be the provider.
2. A **KernelSU-family manager** installed (KernelSU / KernelSU-Next / SukiSU,
   including package-name-spoofed builds). Its bundled `libksud.so` is used for
   the late-load.
3. USB debugging is *not* required; Shizuku can be started via wireless
   debugging on-device.

## Usage

1. Start Shizuku, grant this app permission.
2. Enter your KernelSU manager's package name (default `me.weishu.kernelsu`;
   for a spoofed build, e.g. your custom package).
3. Tap **Root (KernelSU)** and wait ~1–3 minutes.
4. Success shows `KSU_LOADED`. Verify with `adb shell su -c id`
   (expect `context=u:r:ksu:s0`).

The reclaim is **probabilistic**. If a run ends with `parking` / "reclaim
missed", **reboot the device** and tap Root again. Never kill a parked run and
never re-run on the same boot after a park.

## Build

### GitHub Actions (recommended)

Push to `main`; the workflow builds `app/build/outputs/apk/release/*.apk` and
uploads it as the `PuddingRoot-apk` artifact.

Optional stable signing — add repository secrets:

- `KEYSTORE_BASE64` — base64 of a JKS keystore
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Without them the release APK is signed with the CI debug key.

### Local

```sh
./gradlew assembleRelease
```

Requires JDK 17 and the Android SDK (platform 34, build-tools 34.0.0).

## How it works

1. The app copies its bundled payload (`libtimer.so`, a static aarch64 ELF)
   into `/data/local/tmp/a/ptimer` through Shizuku's shell context.
2. The payload runs in `u:r:shell:s0` (`uid=2000`) and exploits CVE-2026-98260
   (POSIX CPU timer UAF → `rb_erase` 8-byte zero-write).
3. It forges a `cred` with `uid=0` + full caps, then runs
   `ksud late-load --kmi android16-6.12 --package-name <your manager>`, which
   loads the matching KernelSU LKM and restores SELinux enforcing.

The manager package is passed via the `KSU_PKG` environment variable, so the
same binary works with stock/spoofed managers.

## Credits

- Exploit: [Dream-Rainy/CVE-2026-98260](https://github.com/Dream-Rainy/CVE-2026-98260),
  derived from [YuKongA/CVE-2026-98260](https://github.com/YuKongA/CVE-2026-98260).
- Architecture reference: [zsw125/o3root](https://github.com/zsw125/o3root)
  (Shizuku shell → CVE-2026-98260 → KernelSU LKM).
- Shizuku: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

No license is claimed over the derived exploit code; see the upstream projects.
