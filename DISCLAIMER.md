# Disclaimer

This project is provided for **security research, education, and repair of
devices you own**.

- **Only run it on hardware you own or are explicitly authorized to test.**
  Using it against devices you do not own is illegal in most jurisdictions.
- **Temporary root is not persistent.** It exists only for the current boot and
  is removed by a reboot. It does **not** unlock the bootloader or modify any
  partition.
- The exploit corrupts kernel memory on purpose. A failed attempt can **panic
  or spontaneously reboot** the device, and can in principle cause data loss.
  Back up important data.
- The reclaim is probabilistic. Expect to reboot and retry several times.
- **Never kill a parked exploit process**, and never re-run on the same boot
  after a run parks. Reboot to clean up.
- The authors accept no liability for data loss, device damage, warranty
  invalidation, or any other consequence. Use at your own risk.

This project bundles exploit code derived from
[Dream-Rainy/CVE-2026-98260](https://github.com/Dream-Rainy/CVE-2026-98260) and
[YuKongA/CVE-2026-98260](https://github.com/YuKongA/CVE-2026-98260). No license
is claimed over that derived code.
