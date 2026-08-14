import 'search_query.dart';
import 'search_result.dart';

/// CairoDrive's destination model — the boundary between search and navigation.
///
/// The navigation layer consumes only this. It never sees a Google prediction,
/// a Magic Lane `Landmark`, or a provider id, which is what keeps routing from
/// becoming coupled to whichever search backend happened to answer.
class Destination {
  const Destination({
    required this.name,
    required this.location,
    this.address,
    this.category,
    this.sourceProvider,
    this.sourcePlaceId,
  });

  final String name;
  final LatLng location;
  final String? address;
  final String? category;

  /// Provenance, retained for analytics/debugging only. Routing ignores it.
  final SearchProviderId? sourceProvider;
  final String? sourcePlaceId;

  double get latitude => location.latitude;
  double get longitude => location.longitude;

  /// Build a destination from a fully-resolved [SearchResult].
  ///
  /// Throws [SearchFailure] if coordinates are still missing — that means the
  /// caller skipped the resolve step, and starting navigation to a null
  /// location would be the bug.
  factory Destination.fromSearchResult(SearchResult result) {
    final location = result.location;
    if (location == null || result.needsDetailsLookup) {
      throw const SearchFailure(
        SearchFailureKind.malformed,
        'Result must be resolved before it can become a destination',
      );
    }
    return Destination(
      name: result.title,
      location: location,
      address: result.subtitle,
      category: result.category,
      sourceProvider: result.provider,
      sourcePlaceId: result.placeId,
    );
  }

  @override
  String toString() => 'Destination("$name", $location)';
}
