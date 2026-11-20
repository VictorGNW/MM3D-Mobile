# Majora's Mask 3D Recomp for Android

An experimental Android port of [MM3D-PC](https://github.com/Volpe34/MM3D-PC), based on the [TriAevum runtime](https://github.com/coccofresco/TriAevum). The build translates the game's 3DS ARM code ahead of time into a native ARM64 module. On Android, SDL2 manages the window and input, and the renderer uses NRI and Vulkan.

The build requires your own decrypted **Majora's Mask 3D USA v1.1** dump (`.3ds` or `.cci`). The source repository does not include the ROM, the generated game module, or game assets.

## Current status

- **Gameplay reached** on a Motorola Edge 60 with Android 17 and a Mali-G615 MC2. Frame pacing was reported stable during a short gameplay test. The driver supports Vulkan 1.3, and the NRI runtime used Vulkan 1.2. Other devices, longer play sessions, and saving still need testing.
- **Android controls:** the touch overlay includes a layout editor, fullscreen mode, and a skip button for the opening sequence. The overlay is configured to hide when a physical gamepad connects and return when the last one disconnects. That transition still needs a test with a real controller.
- **Phone compilation verified in Termux:** the phone compiled and linked the full ARM64 game module from C++ translated on a PC. The Android app loaded that module from private storage and reached the title screen.
- **Standalone installer in development:** the prototype imports a decrypted USA v1.1 dump, runs the bundled Python, Capstone, Clang and LLD, and shows progress, estimated time, and cancellation. ROM import and small C++ builds passed on device. The complete flow from ROM selection to gameplay within a single APK still needs to pass.

## Reporting problems

Include your phone model, Android version, GPU, the scene where the issue occurred, and what you were doing. The app writes `logs/native-stdout.log` and `logs/native-stderr.log` under its data directory. See the [Android build guide](ports/android/MM3D.md) for the ADB command to read them.

The upstream [MM3D-PC project](https://github.com/Volpe34/MM3D-PC) documents the desktop port and credits. See [LICENSE_SCOPE.md](LICENSE_SCOPE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the licensing details in this fork.
