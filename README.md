<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio Mobile

- [Android on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android APK](https://github.com/NuvioMedia/NuvioMobile/releases/latest)
- iOS via AltStore or SideStore: add [this source URL](https://raw.githubusercontent.com/NuvioMedia/NuvioMobile/cmp-rewrite/store.json) in the app's Sources section, then install Nuvio.

## Profile features in this fork

Title details appear immediately from the add-on and update in place when TMDB enrichment finishes, with core metadata loading before series episode enrichment.

New kids profiles start with cast enrichment off and enable TMDB metadata enrichment using the same API key entered during KidsScreen setup. The key stays in that profile’s local credentials. Turn enrichment off at any time in Settings → Integrations → TMDB.

- Kids profiles use KidsScreen catalogs and search with G, PG or PG-13 limits. Nuvio verifies titles before showing them in catalogs, saved libraries, recommendations, downloads and Continue Watching, and before opening details or playing media. Unverifiable titles stay hidden.
- Create or edit profiles with an accent color, custom avatar, background selection, and the recommended OpenSubtitles v3 toggle. Import selected add-ons from any profile without replacing existing providers or their settings.
- New profiles can copy another profile’s layout and preferences once. Saved credentials are excluded and future changes remain independent.

Kids setup requires a signed-in account, a personal TMDB API key and a backend with the existing desktop kids-profile RPCs installed. The primary profile stays the parent profile. KidsScreen holds the encrypted TMDB key; this device stores the configuration management token and keeps the TMDB key in that profile’s local integration settings. Changing a kids rating on a new device may require entering the TMDB key once.

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioMobile.git
cd NuvioMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

### iOS

iOS development requires macOS and Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)

Kids title details retain verified content and loaded artwork while enrichment updates are checked, avoiding blank-screen reloads while still clearing content on title, profile or policy changes.

Details enrichment now publishes core metadata before optional trailers, recommendations and collection items finish. Those sections complete independently, disabled cast/artwork/details options skip their dedicated TMDB requests, and a timeout preserves metadata that has already arrived.

Kids profile setup defaults TMDB production companies and networks off, including when starting from copied settings. Explicit later choices sync between platforms and survive profile edits. Add-on age ratings omit repeated certifications before enrichment arrives.

Editing an existing kids profile shows a Key saved status and reveals the TMDB field only when Replace key is selected. Cancelling a replacement keeps the saved key. Mobile cosmetic profile edits preserve KidsScreen configuration without rerunning setup.

Rating edits from another signed-in device can reuse the server-held key through KidsScreen account-ownership verification. Nuvio session credentials are sent only to the trusted KidsScreen service; custom servers retain capability-based management.

Movie details now show section-specific shimmer placeholders while selected TMDB modules load: one trailer card, cast avatars, production chips, collection/recommendation cards and missing detail-row values. Existing add-on content stays visible, disabled sections stay hidden, and each placeholder clears on completion, empty results, timeout or cancellation. Phone and tablet layouts use the same card sizing and shared skeleton animation as the rest of the mobile app.
