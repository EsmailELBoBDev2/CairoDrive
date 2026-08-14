import 'package:flutter/services.dart';

/// This installed app's own package name and signing-certificate SHA-1
/// fingerprint, read from the Android OS at runtime via [MainActivity]
/// (app/android/.../MainActivity.kt + AppIdentity.kt).
///
/// An Android-app-restricted Google API key is checked against the calling
/// app's true package + signing cert on every request. The Maps SDK attaches
/// this identity automatically; a raw REST call — which is what
/// GooglePlacesSearchProvider issues — must attach it explicitly via the
/// X-Android-Package / X-Android-Cert headers, or Google rejects the request
/// as unidentified, regardless of what is allow-listed in Cloud Console.
///
/// Resolved from PackageManager, never hardcoded: the signing certificate
/// differs between a local debug build and the CI-signed release build, and a
/// hardcoded value would silently go stale the moment either changes.
class AppIdentity {
  const AppIdentity._();

  static const _channel = MethodChannel('com.cairodrive.app/identity');

  /// Resolves once per process. Returns null on iOS (Android-app key
  /// restrictions are an Android-only concept) or if the platform call fails
  /// for any reason — callers must treat a null result as "omit the identity
  /// headers", not as a fatal error; search still works, it just relies on
  /// Cloud Console being configured to accept unidentified callers.
  static Future<({String androidPackage, String certSha1})?> resolve() async {
    try {
      final raw = await _channel.invokeMapMethod<String, String>('resolve');
      if (raw == null) {
        // ignore: avoid_print
        print('[CairoDrive] AppIdentity: native side could not read the '
            'signing certificate (see logcat tag AndroidRuntime for why). '
            'Places requests will go out with no X-Android-* headers.');
        return null;
      }
      final pkg = raw['package'];
      final cert = raw['certSha1'];
      if (pkg == null || cert == null) return null;
      // ignore: avoid_print
      print('[CairoDrive] AppIdentity resolved: package=$pkg certSha1=$cert '
          '— add this exact (package, SHA-1) pair to the GOOGLE_PLACES_API_KEY '
          'key\'s Android app restrictions in Google Cloud Console if search '
          'is rejected.');
      return (androidPackage: pkg, certSha1: cert);
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[CairoDrive] AppIdentity.resolve failed: ${e.code} ${e.message}');
      return null;
    } on MissingPluginException {
      return null; // iOS, or a build predating this channel
    }
  }
}
