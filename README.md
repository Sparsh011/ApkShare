# ApkShare

ApkShare lets you share and install Android apps as files. It runs on Android 13 and newer.

[Download the latest release APK](https://github.com/Sparsh011/ApkShare/releases/latest)

## Share an app

1. Open ApkShare and tap **Share APK file**.
2. Search for an installed app or pick one from the list.
3. Choose WhatsApp or another available destination from Android’s share sheet.

Standalone apps are sent as `.apk` files. If the receiving app rejects APK attachments, return to the picker, turn on **Share APKs as ZIP**, and try again.

Apps installed as multiple APKs are shared as a ZIP containing `base.apk` and every split installed on the sender’s device. The export contains the installation files only. App data and settings are excluded.

## Install on the receiving phone

1. Save the received APK or ZIP and open ApkShare on the receiving phone.
2. Tap **Install APK or ZIP**. If Android asks, allow ApkShare to install unknown apps, then return to ApkShare.
3. Select the received file. For a split app, choose the entire ZIP.
4. Tap **Continue to installer** and approve the installation in Android’s prompt.

Android installs all APKs in the ZIP in one session. The receiving device must be compatible with the bundled splits, and Android’s installation policies apply. [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)

ApkShare uses `QUERY_ALL_PACKAGES` to list installed apps in the current Android profile. It shares a file from its private cache using a temporary read grant. [Android package visibility](https://developer.android.com/training/package-visibility/declaring#all-apps) · [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)

## Build

Use JDK 25 and Android SDK 37, or open the project in Android Studio. Run `./gradlew :app:assembleRelease`. The release build uses the standard Android debug signing configuration.
