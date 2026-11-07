# PuddingRoot

One-tap **temporary root** for the **Xiaomi 17 (`pudding`)** on **HyperOS 4**
(Android 17), supporting the **Global** and **China (CN)** builds.

The app drives [Shizuku](https://github.com/RikkaApps/Shizuku) in shell mode,
runs the [CVE-2026-98260](https://github.com/Dream-Rainy/CVE-2026-98260)
local-privilege-escalation payload, and loads the installed **KernelSU /
KernelSU-Next / SukiSU / ReSukiSU** manager's kernel module via `ksud
late-load`.

No PC, no unlocked bootloader, no partition changes. **Boot-only** — a reboot
removes it, and you re-run the app.

> Research / education / device-repair project. Only run it on devices you own.
> Read [`DISCLAIMER.md`](DISCLAIMER.md) first.

## Supported devices

| build | firmware | kernel | status |
|---|---|---|---|
| Global | HyperOS `4.0.1.0.XPCMIXM.D01` | `6.12.69-android16-6-g0a1dc0413ec7-ab16271006-4k` | verified on hardware |
| China (CN) | HyperOS 4 `…XPCCNXM` | `6.12.69-android16-6-g43b5ab7e247d-abogki561813157-4k` | verified upstream (Dream-Rainy) |

The app auto-selects the matching payload from the build region (CN vs global)
and treats **any** Xiaomi 17 on HyperOS 4 (`4.0.0.0` and above) as an
experimental target rather than hard-blocking it. The exploit offsets are
build-specific — running an unverified build can fail or panic the kernel.

## Requirements

1. **Shizuku** (or a Shizuku-compatible fork) running in shell mode, with this
   app granted permission. `moe.shizuku.privileged.api` must be the provider.
2. A **KernelSU-family manager** installed (KernelSU / KernelSU-Next / SukiSU /
   ReSukiSU, including package-name-spoofed builds). Its bundled `libksud.so` is
   used for the late-load.
3. USB debugging is *not* required; Shizuku can be started via wireless
   debugging on-device.

## Usage

1. Start Shizuku, grant this app permission.
2. Pick your installed manager in the **Root Manager** selector (it auto-detects
   installed KernelSU-family managers; **Custom** lets you type a spoofed
   package name).
3. Tap **Root (KernelSU)** and wait ~1–3 minutes.
4. Success shows `KSU_LOADED`. Verify with `adb shell su -c id`
   (expect `context=u:r:ksu:s0`).

The reclaim is **probabilistic**. If a run ends with `parking` / "reclaim
missed", **reboot the device** and tap Root again. Never kill a parked run and
never re-run on the same boot after a park.

After a successful run the exploit now **exits cleanly** (releases its spray
memory) instead of parking. A **Soft reboot** button then appears (only when
rooted) — it opens your KernelSU manager, which has a working soft-reboot option
that restarts the framework while keeping the loaded LKM, so root survives it. A
full **Reboot** drops the LKM and you must re-root.

> Note: once KernelSU is loaded (jailbreak mode), it spoofs the kernel version
> string; the app therefore identifies the device by its build ID, not `uname`.

> Quirk: while the exploit's kernel state / KernelSU LKM is loaded, a normal
> **power-off may reboot instead of turning off** (the shutdown path can trip the
> exploit's residual kernel state, or the LKM's hooks). Use the app's **Reboot**
> button to return to a clean state; power-off works normally once you've
> rebooted (root is boot-only).

## Build

### GitHub Actions (recommended)

Push to `main`; one push both builds the APK **and** publishes a GitHub
prerelease. The version comes from [`version.txt`](version.txt) — bump it (e.g.
`0.0.2-beta`) before pushing a new release.

The workflow uploads the APK as the `PuddingRoot-apk` artifact and creates a
prerelease (`v<version>`) with the APK attached.

Releases are signed with a committed keystore (`keystore.p12`), so new versions
install over the previous one. Rotate the key (and move it to a secret) before
making the repo public.

### Local

```sh
./gradlew assembleRelease
```

Requires JDK 17 and the Android SDK (platform 34, build-tools 34.0.0).

## How it works

1. The app copies its bundled payload (`libtimer.so` for global or
   `libtimer-cn.so` for China, both static aarch64 ELFs) into
   `/data/local/tmp/a/ptimer` through Shizuku's shell context.
2. The payload runs in `u:r:shell:s0` (`uid=2000`) and exploits CVE-2026-98260
   (POSIX CPU timer UAF → `rb_erase` 8-byte zero-write).
3. It forges a `cred` with `uid=0` + full caps, then runs
   `ksud late-load --kmi android16-6.12 --package-name <your manager>`, which
   loads the matching KernelSU LKM and restores SELinux enforcing.

The manager package is passed via the `KSU_PKG` environment variable, so the
same binary works with stock/spoofed managers. See
[`exploit/`](exploit) for the payload source and [`exploit/build.sh`](exploit/build.sh)
to rebuild both binaries.

## Credits

- Exploit: [Dream-Rainy/CVE-2026-98260](https://github.com/Dream-Rainy/CVE-2026-98260),
  derived from [YuKongA/CVE-2026-98260](https://github.com/YuKongA/CVE-2026-98260).
- Architecture reference: [zsw125/o3root](https://github.com/zsw125/o3root)
  (Shizuku shell → CVE-2026-98260 → KernelSU LKM).
- Shizuku: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

No license is claimed over the derived exploit code; see the upstream projects.
