/// CairoDrive's provider-agnostic search layer.
///
/// Pure Dart on purpose: no Flutter, no map SDK. That keeps the architecture
/// honest (the UI and navigation cannot reach a provider directly) and lets the
/// whole layer be unit-tested without an emulator.
library cairodrive_search;

export 'src/coordinator/search_coordinator.dart';
export 'src/model/destination.dart';
export 'src/model/search_query.dart';
export 'src/model/search_result.dart';
export 'src/providers/google_places_provider.dart';
export 'src/providers/magiclane_search_provider.dart';
export 'src/providers/search_provider.dart';
export 'src/region/egypt.dart';
