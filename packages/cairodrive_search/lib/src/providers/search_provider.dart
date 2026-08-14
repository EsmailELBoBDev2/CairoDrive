import '../model/search_query.dart';
import '../model/search_result.dart';

/// The contract every search backend implements.
///
/// Adding an OSM/Nominatim provider later means implementing this and
/// registering it with the [SearchCoordinator] — no UI or navigation change.
abstract interface class SearchProvider {
  /// Stable identity, surfaced on every [SearchResult] this provider returns.
  SearchProviderId get id;

  /// Whether this provider is currently usable (key present, engine ready).
  /// A provider that returns false is skipped without being counted a failure.
  bool get isAvailable;

  /// Return suggestions for [query].
  ///
  /// Throws [SearchFailure] on any error. Must complete quickly or throw
  /// [SearchFailureKind.timeout]; the coordinator relies on that to fall back.
  Future<List<SearchResult>> autocomplete(SearchQuery query);

  /// Resolve a result that has [SearchResult.needsDetailsLookup] set, filling in
  /// coordinates and any metadata needed to navigate to it.
  ///
  /// Providers whose autocomplete already returns coordinates may return
  /// [result] unchanged.
  Future<SearchResult> resolve(SearchResult result);

  /// Abandon any in-flight work. Called when the user keeps typing.
  void cancelInFlight();
}
