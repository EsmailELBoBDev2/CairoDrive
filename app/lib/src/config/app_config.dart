/// Build-time configuration.
///
/// Keys arrive via `--dart-define`, which the Gradle build populates from
/// `local.properties` (local dev) or the CI environment (GitHub Actions).
/// Nothing here has a real default: an absent key degrades the app gracefully
/// rather than shipping a committed credential.
class AppConfig {
  const AppConfig._();

  /// Google Places API (New) key. Injected from the `GOOGLE_PLACES_API_KEY`
  /// secret. Empty in a build without it — the app then runs on engine search
  /// alone, which is exactly the fallback path.
  static const String googlePlacesApiKey =
      String.fromEnvironment('GOOGLE_PLACES_API_KEY');

  /// Magic Lane SDK project token. Injected from `MAGICLANE_API_KEY`.
  /// Without it the map engine cannot initialise at all.
  static const String magicLaneApiKey =
      String.fromEnvironment('MAGICLANE_API_KEY');

  static bool get hasGooglePlaces => googlePlacesApiKey.isNotEmpty;
  static bool get hasMagicLane => magicLaneApiKey.isNotEmpty;

  /// Never log the values — only whether they are present.
  static String describe() =>
      'AppConfig(googlePlaces: ${hasGooglePlaces ? "set" : "absent"}, '
      'magicLane: ${hasMagicLane ? "set" : "absent"})';
}
