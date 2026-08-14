import 'search_result.dart';

/// Everything a provider needs to answer one autocomplete request.
class SearchQuery {
  const SearchQuery({
    required this.text,
    this.origin,
    this.biasRadiusMeters = 50000,
    this.regionCode = 'EG',
    this.languageCode,
  });

  /// Raw user input. Providers must treat this as untrusted text.
  final String text;

  /// Current device position, when available. Drives location bias and the
  /// `distanceMeters` field in Google predictions.
  final LatLng? origin;

  /// Radius of the circular location bias around [origin].
  final double biasRadiusMeters;

  /// ISO-3166-1 region used to regionalise results. Defaults to Egypt.
  final String regionCode;

  /// BCP-47 language. When null the provider infers it from the query script
  /// (Arabic input → `ar`, otherwise `en`).
  final String? languageCode;

  bool get isEmpty => text.trim().isEmpty;

  SearchQuery copyWith({String? text, LatLng? origin}) => SearchQuery(
        text: text ?? this.text,
        origin: origin ?? this.origin,
        biasRadiusMeters: biasRadiusMeters,
        regionCode: regionCode,
        languageCode: languageCode,
      );
}

/// Why a provider failed. The UI renders a different state per kind, so this is
/// deliberately explicit rather than a single opaque error.
enum SearchFailureKind {
  /// No connectivity / DNS / socket failure. Includes airplane mode.
  network,

  /// The request exceeded the provider timeout.
  timeout,

  /// HTTP 429, or a quota/billing rejection.
  quota,

  /// Authentication rejected the key (HTTP 401/403).
  auth,

  /// Any other non-success HTTP status.
  http,

  /// The response was not the JSON shape we expect.
  malformed,

  /// The request was superseded by a newer query and dropped.
  cancelled,
}

/// Raised by a [SearchProvider] when a request cannot be fulfilled.
class SearchFailure implements Exception {
  const SearchFailure(this.kind, this.message, {this.statusCode});

  final SearchFailureKind kind;
  final String message;
  final int? statusCode;

  /// Whether the coordinator should try the fallback provider. A cancelled
  /// request must never trigger a fallback — the user simply typed on.
  bool get shouldFallBack => kind != SearchFailureKind.cancelled;

  @override
  String toString() =>
      'SearchFailure(${kind.name}${statusCode == null ? '' : ' $statusCode'}: $message)';
}
