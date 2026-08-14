/// The single internal result type shared by every search provider.
///
/// The UI and the navigation layer only ever see this type — they must not be
/// able to tell whether a result came from Google Places, Magic Lane, or a
/// future OSM provider.
library;

/// Which backend produced a [SearchResult].
enum SearchProviderId {
  /// Google Places API (New).
  google,

  /// Magic Lane Maps SDK (on-device / engine search).
  magicLane,

  /// Reserved for a future OSM/Nominatim provider.
  osm,
}

extension SearchProviderIdName on SearchProviderId {
  String get wireName => switch (this) {
        SearchProviderId.google => 'google',
        SearchProviderId.magicLane => 'magiclane',
        SearchProviderId.osm => 'osm',
      };
}

/// A geographic point. Kept local to this package so the search layer has no
/// dependency on Flutter or on any map SDK.
class LatLng {
  const LatLng(this.latitude, this.longitude);

  final double latitude;
  final double longitude;

  @override
  bool operator ==(Object other) =>
      other is LatLng &&
      other.latitude == latitude &&
      other.longitude == longitude;

  @override
  int get hashCode => Object.hash(latitude, longitude);

  @override
  String toString() =>
      'LatLng(${latitude.toStringAsFixed(6)}, ${longitude.toStringAsFixed(6)})';
}

/// One search suggestion or resolved place.
///
/// A result coming straight out of Google Autocomplete has no coordinates yet —
/// Autocomplete (New) does not return a location. Such a result carries
/// [needsDetailsLookup] `true` and null [location]; the coordinates are filled
/// in by a Place Details (New) call *only once the user selects it*.
class SearchResult {
  const SearchResult({
    required this.provider,
    required this.id,
    required this.title,
    this.placeId,
    this.subtitle,
    this.location,
    this.category,
    this.distanceMeters,
    this.needsDetailsLookup = false,
  });

  /// Which provider produced this result.
  final SearchProviderId provider;

  /// Stable identifier within [provider]. For Google this equals [placeId];
  /// for Magic Lane it is a synthesized landmark identity.
  final String id;

  /// Google Places place ID, when the result originated from Google.
  final String? placeId;

  /// Primary display line (place / business name).
  final String title;

  /// Secondary display line (address or context).
  final String? subtitle;

  /// Coordinates, or null when they still need to be resolved.
  final LatLng? location;

  /// Place type/category, e.g. `shopping_mall`. Provider-normalised.
  final String? category;

  /// Straight-line distance from the search origin, when known.
  final double? distanceMeters;

  /// True when [location] is null and a details lookup is required before this
  /// result can be used as a navigation destination.
  final bool needsDetailsLookup;

  bool get hasLocation => location != null;

  double? get latitude => location?.latitude;
  double? get longitude => location?.longitude;

  SearchResult copyWith({
    LatLng? location,
    String? subtitle,
    String? category,
    double? distanceMeters,
    bool? needsDetailsLookup,
  }) {
    return SearchResult(
      provider: provider,
      id: id,
      placeId: placeId,
      title: title,
      subtitle: subtitle ?? this.subtitle,
      location: location ?? this.location,
      category: category ?? this.category,
      distanceMeters: distanceMeters ?? this.distanceMeters,
      needsDetailsLookup: needsDetailsLookup ?? this.needsDetailsLookup,
    );
  }

  @override
  String toString() =>
      'SearchResult(${provider.wireName}, "$title", ${location ?? "no-location"})';
}
