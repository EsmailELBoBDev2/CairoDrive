import 'dart:async';

import 'package:cairodrive_search/cairodrive_search.dart';

import '../engine/engine_ports.dart';

/// Stages of the handoff from a tapped search result to active guidance.
enum TripStage { idle, resolving, routing, previewing, navigating, failed }

/// The single path from a search result to navigation.
///
/// This is the class the brief's critical flow describes:
///
///   Google Place → SearchResult → Destination → Magic Lane routing → navigation
///
/// Both providers funnel through here, so a Google result and an engine result
/// start navigation by exactly the same code path — there is no second routing
/// implementation and no Google-specific branch below this point.
class DestinationController {
  DestinationController({
    required SearchCoordinator search,
    required RoutingEngine routing,
    required NavigationEngine navigation,
  })  : _search = search,
        _routing = routing,
        _navigation = navigation;

  final SearchCoordinator _search;
  final RoutingEngine _routing;
  final NavigationEngine _navigation;

  final _stages = StreamController<TripStage>.broadcast();
  Stream<TripStage> get stages => _stages.stream;

  TripStage _stage = TripStage.idle;
  TripStage get stage => _stage;

  Destination? _destination;
  Destination? get destination => _destination;

  List<RouteSummary> _routes = const [];
  List<RouteSummary> get routes => _routes;

  String? lastError;

  /// Take a tapped search result all the way to a route preview.
  ///
  /// For a Google result this is the moment — and the only moment — a Place
  /// Details call is issued, closing the autocomplete session.
  Future<bool> selectResult(SearchResult result) async {
    lastError = null;
    try {
      _setStage(TripStage.resolving);
      // Resolve coordinates if the provider deferred them (Google always does).
      final resolved = await _search.select(result);

      // Provider-specific data stops here. Below this line everything is
      // CairoDrive's own Destination type.
      final destination = Destination.fromSearchResult(resolved);
      _destination = destination;

      _setStage(TripStage.routing);
      final routes = await _routing.calculateRoute(to: destination);
      if (routes.isEmpty) {
        lastError = 'No route could be calculated to ${destination.name}.';
        _setStage(TripStage.failed);
        return false;
      }
      _routes = routes;
      _setStage(TripStage.previewing);
      return true;
    } on SearchFailure catch (f) {
      lastError = switch (f.kind) {
        SearchFailureKind.network => 'No connection — could not load place details.',
        SearchFailureKind.timeout => 'Place details timed out. Try again.',
        SearchFailureKind.quota => 'Search quota exceeded. Try again later.',
        SearchFailureKind.auth => 'Search is not configured correctly.',
        _ => 'Could not open that place: ${f.message}',
      };
      _setStage(TripStage.failed);
      return false;
    } catch (e) {
      lastError = 'Could not start a trip: $e';
      _setStage(TripStage.failed);
      return false;
    }
  }

  /// Start guidance on one of the previewed routes.
  Future<bool> startNavigation({int routeIndex = 0}) async {
    if (routeIndex < 0 || routeIndex >= _routes.length) {
      lastError = 'That route is no longer available.';
      _setStage(TripStage.failed);
      return false;
    }
    try {
      await _navigation.startNavigation(_routes[routeIndex]);
      _setStage(TripStage.navigating);
      return true;
    } catch (e) {
      lastError = 'Could not start navigation: $e';
      _setStage(TripStage.failed);
      return false;
    }
  }

  /// Route directly to an already-known point (Android Auto "drive to", a
  /// `geo:` intent, a favourite). Skips search entirely but reuses the same
  /// routing and navigation path.
  Future<bool> navigateToDestination(Destination destination) async {
    lastError = null;
    _destination = destination;
    try {
      _setStage(TripStage.routing);
      final routes = await _routing.calculateRoute(to: destination);
      if (routes.isEmpty) {
        lastError = 'No route to ${destination.name}.';
        _setStage(TripStage.failed);
        return false;
      }
      _routes = routes;
      _setStage(TripStage.previewing);
      // Awaited so a failure inside startNavigation is caught below rather
      // than escaping as an unhandled rejection.
      return await startNavigation();
    } catch (e) {
      lastError = 'Could not start navigation: $e';
      _setStage(TripStage.failed);
      return false;
    }
  }

  Future<void> cancelTrip() async {
    _routing.cancelCalculation();
    await _navigation.stopNavigation();
    _destination = null;
    _routes = const [];
    _setStage(TripStage.idle);
  }

  void _setStage(TripStage stage) {
    _stage = stage;
    if (!_stages.isClosed) _stages.add(stage);
  }

  void dispose() {
    _stages.close();
  }
}
