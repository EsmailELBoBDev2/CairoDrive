/// Magic Lane implementations of CairoDrive's engine ports.
///
/// This is the ONLY place in the app that imports `magiclane_maps_flutter`.
/// Everything else depends on `engine_ports.dart`, so a change of map engine —
/// or a breaking change in this SDK — is contained to this file.
///
/// Signatures here were verified against magiclane_maps_flutter 3.1.11 sources,
/// not inferred: `SearchService.search` / `RoutingService.calculateRoute` /
/// `NavigationService.startNavigation` all return a `TaskHandler?`, and the
/// matching cancel calls require that handle back.
library;

import 'dart:async';

import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:magiclane_maps_flutter/magiclane_maps_flutter.dart' as gem;

import '../engine_ports.dart';

/// Engine lifecycle backed by `GemKit`.
class MagicLaneMapEngine implements MapEngine {
  bool _ready = false;
  LatLng? _lastPosition;

  @override
  bool get isReady => _ready;

  @override
  LatLng? get currentPosition => _lastPosition;

  @override
  Future<void> initialize({required String apiToken}) async {
    if (apiToken.isEmpty) {
      throw StateError(
        'Magic Lane API token is missing. Build with '
        '--dart-define=MAGICLANE_API_KEY=<token> (see README).',
      );
    }
    await gem.GemKit.initialize(appAuthorization: apiToken);
    _ready = true;
  }

  /// Cache the latest fix so search can bias to it.
  void updatePosition(double latitude, double longitude) {
    _lastPosition = LatLng(latitude, longitude);
  }

  @override
  Future<void> dispose() async {
    if (!_ready) return;
    await gem.GemKit.release();
    _ready = false;
  }
}

/// Bridges the search package's engine port onto `SearchService`.
///
/// Implementing [SearchEngine] here is what lets `cairodrive_search` stay pure
/// Dart while still using the on-device engine as its offline fallback.
class MagicLaneSearchAdapter implements SearchEngine {
  MagicLaneSearchAdapter(this._engine);

  final MagicLaneMapEngine _engine;

  /// Retained so an in-flight engine search can actually be cancelled — the SDK
  /// requires the originating handle.
  gem.TaskHandler? _inFlight;

  @override
  bool get isReady => _engine.isReady;

  @override
  void cancelSearch() {
    final handle = _inFlight;
    if (handle == null || !_engine.isReady) return;
    gem.SearchService.cancelSearch(handle);
    _inFlight = null;
  }

  @override
  Future<List<EngineLandmark>> searchByText({
    required String text,
    double? originLatitude,
    double? originLongitude,
  }) {
    final fallbackOrigin = _engine.currentPosition ?? EgyptRegion.cairoCenter;
    final reference = gem.Coordinates(
      latitude: originLatitude ?? fallbackOrigin.latitude,
      longitude: originLongitude ?? fallbackOrigin.longitude,
    );

    final completer = Completer<List<EngineLandmark>>();
    _inFlight = gem.SearchService.search(text, reference,
        (gem.GemError err, List<gem.Landmark> results) {
      _inFlight = null;
      if (completer.isCompleted) return;

      if (err != gem.GemError.success && err != gem.GemError.reducedResult) {
        completer.completeError(
          SearchFailure(
            err == gem.GemError.cancel
                ? SearchFailureKind.cancelled
                : SearchFailureKind.http,
            'Engine search failed: ${err.name}',
          ),
        );
        return;
      }

      completer.complete([
        for (final landmark in results)
          EngineLandmark(
            name: landmark.name,
            latitude: landmark.coordinates.latitude,
            longitude: landmark.coordinates.longitude,
          ),
      ]);
    });
    return completer.future;
  }
}

/// Routing backed by `RoutingService`. CairoDrive never computes a route
/// itself — this simply asks the engine.
class MagicLaneRoutingEngine implements RoutingEngine {
  MagicLaneRoutingEngine(this._engine);

  final MagicLaneMapEngine _engine;
  gem.TaskHandler? _inFlight;

  @override
  void cancelCalculation() {
    final handle = _inFlight;
    if (handle == null || !_engine.isReady) return;
    gem.RoutingService.cancelRoute(handle);
    _inFlight = null;
  }

  @override
  Future<List<RouteSummary>> calculateRoute({
    required Destination to,
    LatLng? from,
  }) {
    if (!_engine.isReady) {
      return Future.error(StateError('Map engine is not initialised'));
    }
    final origin = from ?? _engine.currentPosition;
    if (origin == null) {
      return Future.error(
          StateError('No current position available to route from'));
    }

    final waypoints = <gem.Landmark>[
      gem.Landmark.withLatLng(
        latitude: origin.latitude,
        longitude: origin.longitude,
      )..name = 'Start',
      gem.Landmark.withLatLng(
        latitude: to.latitude,
        longitude: to.longitude,
      )..name = to.name,
    ];

    final completer = Completer<List<RouteSummary>>();
    _inFlight = gem.RoutingService.calculateRoute(
        waypoints, gem.RoutePreferences(),
        (gem.GemError err, List<gem.Route> routes) {
      _inFlight = null;
      if (completer.isCompleted) return;

      if (err != gem.GemError.success || routes.isEmpty) {
        completer.completeError(
            StateError('Route calculation failed: ${err.name}'));
        return;
      }

      completer.complete([
        for (final route in routes)
          RouteSummary(
            handle: route,
            distanceMeters: route.getTimeDistance().totalDistanceM,
            durationSeconds: route.getTimeDistance().totalTimeS,
            summary: route.summary,
          ),
      ]);
    });
    return completer.future;
  }
}

/// Turn-by-turn guidance backed by `NavigationService`.
class MagicLaneNavigationEngine implements NavigationEngine {
  MagicLaneNavigationEngine(this._engine);

  final MagicLaneMapEngine _engine;

  final _instructions = StreamController<GuidanceInstruction>.broadcast();
  final _rerouted = StreamController<RouteSummary>.broadcast();
  final _arrived = StreamController<void>.broadcast();

  gem.TaskHandler? _session;

  @override
  bool get isNavigating => _session != null;

  @override
  Stream<GuidanceInstruction> get instructions => _instructions.stream;

  @override
  Stream<RouteSummary> get rerouted => _rerouted.stream;

  @override
  Stream<void> get destinationReached => _arrived.stream;

  @override
  Future<void> startNavigation(RouteSummary route) async {
    if (!_engine.isReady) throw StateError('Map engine is not initialised');
    final handle = route.handle;
    if (handle is! gem.Route) {
      throw ArgumentError('RouteSummary.handle is not a Magic Lane Route');
    }

    _session = gem.NavigationService.startNavigation(
      handle,
      onNavigationInstruction: (gem.NavigationInstruction instruction, _) {
        if (_instructions.isClosed) return;
        _instructions.add(GuidanceInstruction(
          text: instruction.nextStreetName,
          distanceToTurnMeters:
              instruction.timeDistanceToNextTurn.totalDistanceM,
          remainingDistanceMeters:
              instruction.remainingTravelTimeDistance.totalDistanceM,
          remainingSeconds: instruction.remainingTravelTimeDistance.totalTimeS,
        ));
      },
      // Fired when the engine recalculates after a deviation — this is what
      // makes rerouting flow through the same RouteSummary type as the
      // original calculation.
      onRouteUpdated: (gem.Route updated) {
        if (_rerouted.isClosed) return;
        _rerouted.add(RouteSummary(
          handle: updated,
          distanceMeters: updated.getTimeDistance().totalDistanceM,
          durationSeconds: updated.getTimeDistance().totalTimeS,
          summary: updated.summary,
        ));
      },
      onDestinationReached: (_) {
        _session = null;
        if (!_arrived.isClosed) _arrived.add(null);
      },
    );
  }

  @override
  Future<void> stopNavigation() async {
    final session = _session;
    if (session == null) return;
    gem.NavigationService.cancelNavigation(session);
    _session = null;
  }

  Future<void> dispose() async {
    await stopNavigation();
    await _instructions.close();
    await _rerouted.close();
    await _arrived.close();
  }
}
