/// CairoDrive's engine abstraction.
///
/// Everything above this file talks to these interfaces only. Magic Lane is the
/// initial implementation, confined to `engine/magiclane/`. Swapping or adding a
/// map engine means writing new adapters, not touching the UI, the search layer,
/// or the navigation flow.
library;

import 'package:cairodrive_search/cairodrive_search.dart';

/// A calculated route, reduced to what CairoDrive's UI actually renders.
class RouteSummary {
  const RouteSummary({
    required this.handle,
    required this.distanceMeters,
    required this.durationSeconds,
    this.summary,
  });

  /// Opaque engine-side route object. Only adapters may unwrap it.
  final Object handle;

  final int distanceMeters;
  final int durationSeconds;
  final String? summary;

  Duration get duration => Duration(seconds: durationSeconds);
}

/// One turn instruction during active guidance.
class GuidanceInstruction {
  const GuidanceInstruction({
    required this.text,
    this.distanceToTurnMeters,
    this.remainingDistanceMeters,
    this.remainingSeconds,
  });

  final String text;
  final int? distanceToTurnMeters;
  final int? remainingDistanceMeters;
  final int? remainingSeconds;
}

/// Engine lifecycle and authorisation.
abstract interface class MapEngine {
  /// True once the SDK is initialised and the token accepted.
  bool get isReady;

  /// Initialise the SDK with the project token. Must be called before any other
  /// engine port is used.
  Future<void> initialize({required String apiToken});

  /// Last known device position, or null if there is no fix.
  LatLng? get currentPosition;

  Future<void> dispose();
}

/// Route calculation. Deliberately narrow: CairoDrive never implements its own
/// routing, it only asks the engine for one.
abstract interface class RoutingEngine {
  /// Calculate a route from [from] (or current position when null) to [to].
  Future<List<RouteSummary>> calculateRoute({
    required Destination to,
    LatLng? from,
  });

  /// Abandon an in-progress calculation.
  void cancelCalculation();
}

/// Turn-by-turn guidance.
abstract interface class NavigationEngine {
  bool get isNavigating;

  /// Begin guidance along [route].
  Future<void> startNavigation(RouteSummary route);

  Future<void> stopNavigation();

  /// Instructions as guidance proceeds. Also fires on reroute.
  Stream<GuidanceInstruction> get instructions;

  /// Emits when the engine has recalculated after a deviation.
  Stream<RouteSummary> get rerouted;

  /// Emits once the destination is reached.
  Stream<void> get destinationReached;
}

/// The engine's own search, exposed so it can back the fallback provider.
///
/// This is intentionally the same shape as [MapEngineSearchPort] from the search
/// package — the adapter implements that interface directly.
typedef SearchEngine = MapEngineSearchPort;
