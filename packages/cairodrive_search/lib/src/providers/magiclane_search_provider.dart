import 'dart:async';

import '../model/search_query.dart';
import '../model/search_result.dart';
import 'search_provider.dart';

/// A single place as returned by the map engine.
///
/// This mirrors the useful part of Magic Lane's `Landmark` without importing
/// the SDK, which keeps this package pure Dart and unit-testable.
class EngineLandmark {
  const EngineLandmark({
    required this.name,
    required this.latitude,
    required this.longitude,
    this.address,
    this.category,
    this.distanceMeters,
  });

  final String name;
  final double latitude;
  final double longitude;
  final String? address;
  final String? category;
  final double? distanceMeters;
}

/// The port the app implements over `magiclane_maps_flutter`'s `SearchService`.
///
/// Declaring it here — rather than depending on the SDK — is what stops
/// CairoDrive's search layer becoming coupled to Magic Lane. Swapping the map
/// engine later means writing one new adapter.
abstract interface class MapEngineSearchPort {
  /// True once the SDK is initialised and authorised.
  bool get isReady;

  /// Free-text search near [origin]. Implementations delegate to
  /// `SearchService.search` / `searchByFilter`.
  Future<List<EngineLandmark>> searchByText({
    required String text,
    double? originLatitude,
    double? originLongitude,
  });

  /// Cancel any in-flight engine search (`SearchService.cancelSearch`).
  void cancelSearch();
}

/// Fallback provider backed by the on-device Magic Lane engine.
///
/// Kept as the fallback rather than the primary because the engine indexes map
/// data, which is excellent for streets and navigation targets but weaker than
/// Google for business/brand discovery — the thing CairoDrive users search for.
/// It keeps working with no network, which is exactly what a fallback needs.
class MagicLaneSearchProvider implements SearchProvider {
  MagicLaneSearchProvider({
    required MapEngineSearchPort engine,
    this.timeout = const Duration(seconds: 10),
    this.maxResults = 20,
  }) : _engine = engine;

  final MapEngineSearchPort _engine;
  final Duration timeout;
  final int maxResults;

  int _requestSeq = 0;

  @override
  SearchProviderId get id => SearchProviderId.magicLane;

  @override
  bool get isAvailable => _engine.isReady;

  @override
  void cancelInFlight() {
    _requestSeq++;
    _engine.cancelSearch();
  }

  @override
  Future<List<SearchResult>> autocomplete(SearchQuery query) async {
    if (query.isEmpty) return const [];
    if (!isAvailable) {
      throw const SearchFailure(
          SearchFailureKind.auth, 'Map engine is not initialised');
    }

    final seq = ++_requestSeq;
    final List<EngineLandmark> landmarks;
    try {
      landmarks = await _engine
          .searchByText(
            text: query.text,
            originLatitude: query.origin?.latitude,
            originLongitude: query.origin?.longitude,
          )
          .timeout(timeout);
    } on TimeoutException {
      throw SearchFailure(SearchFailureKind.timeout,
          'Engine search exceeded ${timeout.inSeconds}s');
    } on SearchFailure {
      rethrow;
    } catch (e) {
      throw SearchFailure(SearchFailureKind.malformed, 'Engine search failed: $e');
    }

    if (seq != _requestSeq) {
      throw const SearchFailure(
          SearchFailureKind.cancelled, 'Superseded by a newer query');
    }

    return [
      for (final (index, l) in landmarks.take(maxResults).indexed)
        SearchResult(
          provider: SearchProviderId.magicLane,
          id: 'ml:$index:${l.latitude.toStringAsFixed(5)},${l.longitude.toStringAsFixed(5)}',
          title: l.name,
          subtitle: l.address,
          location: LatLng(l.latitude, l.longitude),
          category: l.category,
          distanceMeters: l.distanceMeters,
          // The engine always returns coordinates — nothing left to resolve.
          needsDetailsLookup: false,
        ),
    ];
  }

  @override
  Future<SearchResult> resolve(SearchResult result) async => result;
}
