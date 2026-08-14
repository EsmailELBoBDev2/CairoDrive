import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:http/http.dart' as http;

import '../model/search_query.dart';
import '../model/search_result.dart';
import '../region/egypt.dart';
import 'search_provider.dart';

/// Generates the session tokens that tie an autocomplete session to its
/// terminating Place Details call.
///
/// Google bills Autocomplete-plus-Details as one session when both carry the
/// same token, and recommends a v4 UUID. A session ends when Details is issued;
/// the next keystroke after that starts a new one.
class SessionTokenFactory {
  SessionTokenFactory({math.Random? random})
      : _random = random ?? math.Random.secure();

  final math.Random _random;

  /// RFC 4122 version 4 UUID.
  String newToken() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10
    String hex(int start, int end) => bytes
        .sublist(start, end)
        .map((b) => b.toRadixString(16).padLeft(2, '0'))
        .join();
    return '${hex(0, 4)}-${hex(4, 6)}-${hex(6, 8)}-${hex(8, 10)}-${hex(10, 16)}';
  }
}

/// Google Places API (New) provider.
///
/// Implements the session-token lifecycle Google documents:
/// a fresh token is minted for a new typing session, reused across every
/// keystroke in that session, sent again on the terminating Place Details call,
/// and then discarded.
///
/// Field masks are always explicit — never `*` — so we pay for and receive only
/// the fields the navigation use case needs.
class GooglePlacesSearchProvider implements SearchProvider {
  GooglePlacesSearchProvider({
    required String apiKey,
    http.Client? client,
    SessionTokenFactory? tokenFactory,
    this.timeout = const Duration(seconds: 8),
    this.maxSuggestions = 8,
  })  : _apiKey = apiKey,
        _client = client ?? http.Client(),
        _tokens = tokenFactory ?? SessionTokenFactory();

  static const String _autocompleteUrl =
      'https://places.googleapis.com/v1/places:autocomplete';
  static const String _detailsBase = 'https://places.googleapis.com/v1/places/';

  /// Autocomplete needs only what the result row renders plus the id we later
  /// resolve. No coordinates are available from Autocomplete (New) at all.
  static const String _autocompleteFieldMask =
      'suggestions.placePrediction.placeId,'
      'suggestions.placePrediction.text,'
      'suggestions.placePrediction.structuredFormat,'
      'suggestions.placePrediction.types,'
      'suggestions.placePrediction.distanceMeters';

  /// The minimum that turns a selection into a navigable destination.
  static const String _detailsFieldMask =
      'id,displayName,formattedAddress,location,primaryType';

  final String _apiKey;
  final http.Client _client;
  final SessionTokenFactory _tokens;
  final Duration timeout;
  final int maxSuggestions;

  /// This app's true package name / signing-cert SHA-1, set once
  /// [AppIdentity.resolve] (app/lib/src/config/app_identity.dart) completes.
  ///
  /// Mutable and set post-construction rather than required in the
  /// constructor: identity resolution is an async platform call, and the
  /// provider must exist synchronously during app wire-up so the UI has
  /// something to bind to immediately. Until this is set, requests go out
  /// with no X-Android-* headers — same behaviour as before this existed.
  ///
  /// An Android-app-restricted API key checks these on every request; the
  /// Places SDK attaches them automatically, but this provider issues raw
  /// HTTP, so it must attach them itself or the key rejects every call as
  /// unidentified regardless of what is allow-listed in Cloud Console.
  String? androidPackage;
  String? androidCertSha1;

  String? _sessionToken;
  int _requestSeq = 0;

  @override
  SearchProviderId get id => SearchProviderId.google;

  @override
  bool get isAvailable => _apiKey.isNotEmpty;

  /// The token for the current session, minted lazily on the first keystroke.
  String get _currentSessionToken => _sessionToken ??= _tokens.newToken();

  /// Visible for tests: which token this session is currently using.
  String? get debugSessionToken => _sessionToken;

  /// Abandon the current session without consuming it in a Details call.
  /// Used when the user clears the field or leaves the search screen.
  void resetSession() => _sessionToken = null;

  @override
  void cancelInFlight() => _requestSeq++;

  @override
  Future<List<SearchResult>> autocomplete(SearchQuery query) async {
    if (query.isEmpty) return const [];
    if (!isAvailable) {
      throw const SearchFailure(
          SearchFailureKind.auth, 'Google Places API key is not configured');
    }

    final seq = ++_requestSeq;
    final origin = query.origin;
    final language = query.languageCode ?? EgyptRegion.inferLanguageCode(query.text);

    final body = <String, dynamic>{
      'input': query.text,
      'sessionToken': _currentSessionToken,
      'languageCode': language,
      'regionCode': query.regionCode,
      'locationBias': {
        'circle': {
          'center': {
            'latitude': (origin ?? EgyptRegion.cairoCenter).latitude,
            'longitude': (origin ?? EgyptRegion.cairoCenter).longitude,
          },
          'radius': origin == null
              ? EgyptRegion.greaterCairoRadiusMeters
              : query.biasRadiusMeters,
        },
      },
      // `origin` makes Google return distanceMeters on each prediction.
      if (origin != null)
        'origin': {'latitude': origin.latitude, 'longitude': origin.longitude},
    };

    final json = await _post(
      _autocompleteUrl,
      fieldMask: _autocompleteFieldMask,
      body: body,
    );

    // A newer keystroke superseded this request while it was in flight.
    if (seq != _requestSeq) {
      throw const SearchFailure(
          SearchFailureKind.cancelled, 'Superseded by a newer query');
    }

    final suggestions = json['suggestions'];
    if (suggestions == null) return const []; // no matches is not an error
    if (suggestions is! List) {
      throw const SearchFailure(
          SearchFailureKind.malformed, 'suggestions was not a list');
    }

    final out = <SearchResult>[];
    for (final entry in suggestions) {
      if (entry is! Map) continue;
      final prediction = entry['placePrediction'];
      if (prediction is! Map) continue; // queryPrediction rows are not places
      final placeId = prediction['placeId'];
      if (placeId is! String || placeId.isEmpty) continue;

      final structured = prediction['structuredFormat'];
      final mainText = _nestedText(structured, 'mainText') ??
          _nestedText(prediction, 'text') ??
          placeId;
      final secondary = _nestedText(structured, 'secondaryText');
      final types = prediction['types'];
      final distance = prediction['distanceMeters'];

      out.add(SearchResult(
        provider: SearchProviderId.google,
        id: placeId,
        placeId: placeId,
        title: mainText,
        subtitle: secondary,
        category: (types is List && types.isNotEmpty) ? '${types.first}' : null,
        distanceMeters: distance is num ? distance.toDouble() : null,
        // Autocomplete (New) never returns coordinates.
        needsDetailsLookup: true,
      ));
      if (out.length >= maxSuggestions) break;
    }
    return out;
  }

  @override
  Future<SearchResult> resolve(SearchResult result) async {
    if (!result.needsDetailsLookup) return result;
    final placeId = result.placeId;
    if (placeId == null || placeId.isEmpty) {
      throw const SearchFailure(
          SearchFailureKind.malformed, 'Result has no placeId to resolve');
    }

    // The session token is sent one last time here, which is what closes the
    // billing session. It must not be reused afterwards.
    final token = _sessionToken;
    final uri = Uri.parse('$_detailsBase$placeId').replace(queryParameters: {
      if (token != null) 'sessionToken': token,
    });

    try {
      final json = await _get(uri, fieldMask: _detailsFieldMask);
      final location = json['location'];
      if (location is! Map) {
        throw const SearchFailure(
            SearchFailureKind.malformed, 'Place Details returned no location');
      }
      final lat = location['latitude'];
      final lng = location['longitude'];
      if (lat is! num || lng is! num) {
        throw const SearchFailure(
            SearchFailureKind.malformed, 'Place Details location was not numeric');
      }

      return result.copyWith(
        location: LatLng(lat.toDouble(), lng.toDouble()),
        subtitle: _nestedText(json, 'formattedAddress') ??
            (json['formattedAddress'] is String
                ? json['formattedAddress'] as String
                : null) ??
            result.subtitle,
        category: json['primaryType'] is String
            ? json['primaryType'] as String
            : result.category,
        needsDetailsLookup: false,
      );
    } finally {
      // Session is spent whether or not the call succeeded.
      _sessionToken = null;
    }
  }

  Future<Map<String, dynamic>> _post(
    String url, {
    required String fieldMask,
    required Map<String, dynamic> body,
  }) =>
      _send(() => _client.post(
            Uri.parse(url),
            headers: _headers(fieldMask)..['Content-Type'] = 'application/json',
            body: jsonEncode(body),
          ));

  Future<Map<String, dynamic>> _get(Uri uri, {required String fieldMask}) =>
      _send(() => _client.get(uri, headers: _headers(fieldMask)));

  Map<String, String> _headers(String fieldMask) => {
        'X-Goog-Api-Key': _apiKey,
        'X-Goog-FieldMask': fieldMask,
        if (androidPackage != null) 'X-Android-Package': androidPackage!,
        if (androidCertSha1 != null) 'X-Android-Cert': androidCertSha1!,
      };

  /// True identity of the running app this request is sent with, for
  /// diagnostics only — never affects the request itself.
  String get _identityDebugDescription =>
      (androidPackage != null && androidCertSha1 != null)
          ? '$androidPackage / $androidCertSha1'
          : 'NO X-Android-* headers (identity not resolved yet)';

  Future<Map<String, dynamic>> _send(
      Future<http.Response> Function() request) async {
    late final http.Response response;
    try {
      response = await request().timeout(timeout);
    } on TimeoutException {
      throw SearchFailure(
          SearchFailureKind.timeout, 'Places request exceeded ${timeout.inSeconds}s');
    } on SocketException catch (e) {
      throw SearchFailure(SearchFailureKind.network, 'Network unavailable: ${e.message}');
    } on http.ClientException catch (e) {
      throw SearchFailure(SearchFailureKind.network, 'HTTP client error: ${e.message}');
    }

    final status = response.statusCode;
    if (status == 429) {
      throw SearchFailure(SearchFailureKind.quota,
          'Places quota exceeded', statusCode: status);
    }
    if (status == 401 || status == 403) {
      final detail = _describeGoogleError(response.body);
      // ignore: avoid_print
      print('[CairoDrive] Places auth rejected (HTTP $status): $detail. '
          'Request sent as: $_identityDebugDescription.');
      throw SearchFailure(SearchFailureKind.auth,
          'Places rejected the API key: $detail', statusCode: status);
    }
    if (status < 200 || status >= 300) {
      final detail = _describeGoogleError(response.body);
      // ignore: avoid_print
      print('[CairoDrive] Places returned HTTP $status: $detail');
      throw SearchFailure(SearchFailureKind.http,
          'Places returned HTTP $status: $detail', statusCode: status);
    }

    try {
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) {
        throw const SearchFailure(
            SearchFailureKind.malformed, 'Response was not a JSON object');
      }
      return decoded;
    } on FormatException catch (e) {
      throw SearchFailure(
          SearchFailureKind.malformed, 'Invalid JSON: ${e.message}');
    }
  }

  /// Extracts the actual reason from Google's error body, e.g.
  /// `PERMISSION_DENIED — API_KEY_ANDROID_APP_BLOCKED — <human message>`,
  /// instead of the generic "rejected the API key" every 401/403 used to
  /// collapse to. Google's shape:
  ///   {"error": {"code":403, "message":"...", "status":"PERMISSION_DENIED",
  ///              "details":[{"reason":"API_KEY_ANDROID_APP_BLOCKED", ...}]}}
  /// Falls back to a short body excerpt if the shape doesn't match, since an
  /// unparsed body is still more useful for debugging than nothing.
  static String _describeGoogleError(String body) {
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map && decoded['error'] is Map) {
        final err = decoded['error'] as Map;
        String? reason;
        final details = err['details'];
        if (details is List) {
          for (final d in details) {
            if (d is Map && d['reason'] is String) {
              reason = d['reason'] as String;
              break;
            }
          }
        }
        final parts = <String>[
          if (err['status'] is String) err['status'] as String,
          if (reason != null) reason,
          if (err['message'] is String) err['message'] as String,
        ];
        if (parts.isNotEmpty) return parts.join(' — ');
      }
    } catch (_) {
      // fall through to the raw-body fallback below
    }
    final excerpt = body.length > 200 ? '${body.substring(0, 200)}…' : body;
    return excerpt.isEmpty ? '(empty response body)' : excerpt;
  }

  /// Reads `{"field": {"text": "..."}}` or a bare `{"field": "..."}`.
  static String? _nestedText(Object? container, String key) {
    if (container is! Map) return null;
    final value = container[key];
    if (value is String) return value;
    if (value is Map && value['text'] is String) return value['text'] as String;
    return null;
  }

  void dispose() => _client.close();
}
