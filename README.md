# ApkShare

ApkShare lets you share installed Android apps as files. It runs on Android 13 and newer.

[Download the latest release APK](https://github.com/Sparsh011/ApkShare/releases/latest)

1. Open ApkShare and tap **Share APK file**.
2. Search for an installed app or pick one from the list.
3. Choose WhatsApp or another available destination from Android’s share sheet.

Standalone apps are sent as `.apk` files. If the receiving app rejects APK attachments, return to the picker, turn on **Share APKs as ZIP**, and try again. The recipient can extract the APK from the ZIP.

Apps installed as multiple APKs are always shared as a ZIP containing `base.apk` and every split installed on the sender’s device. To install a split app, extract the archive and install the APKs together, for example with `adb install-multiple base.apk split_*.apk`. The receiving device must be compatible with those splits. The export contains the installation files only. App data and settings are excluded.

ApkShare uses `QUERY_ALL_PACKAGES` to list installed apps in the current Android profile. It shares a file from its private cache using a temporary read grant. [Android package visibility](https://developer.android.com/training/package-visibility/declaring#all-apps) · [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)

## Build

Use JDK 25 and Android SDK 37, or open the project in Android Studio. Run `./gradlew :app:assembleRelease`. The release build uses the standard Android debug signing configuration.
