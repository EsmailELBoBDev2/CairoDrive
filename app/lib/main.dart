import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:flutter/material.dart';
import 'package:magiclane_maps_flutter/magiclane_maps_flutter.dart' as gem;

import 'src/config/app_config.dart';
import 'src/config/app_identity.dart';
import 'src/engine/engine_ports.dart';
import 'src/engine/magiclane/magiclane_adapters.dart';
import 'src/navigation/destination_controller.dart';
import 'src/ui/search/search_screen.dart';

void main() {
  runApp(const CairoDriveApp());
}

class CairoDriveApp extends StatefulWidget {
  const CairoDriveApp({super.key});

  @override
  State<CairoDriveApp> createState() => _CairoDriveAppState();
}

class _CairoDriveAppState extends State<CairoDriveApp> {
  late final MagicLaneMapEngine _mapEngine;
  late final MagicLaneRoutingEngine _routing;
  late final MagicLaneNavigationEngine _navigation;
  late final SearchCoordinator _search;
  late final DestinationController _destinations;
  late final GooglePlacesSearchProvider _google;

  String? _initError;
  bool _initialised = false;

  @override
  void initState() {
    super.initState();
    _wireUp();
    _initialiseEngine();
    _resolveGoogleIdentity();
  }

  /// Fills in the app's true (package, signing-cert SHA-1) on the Google
  /// provider once the platform channel resolves. Fire-and-forget: this is a
  /// fast local PackageManager call (no network), typically done well before
  /// the user finishes typing a 2-character query, and every search made
  /// before it resolves still works — just without the identity headers an
  /// Android-app-restricted key needs. See AppIdentity for why this exists.
  Future<void> _resolveGoogleIdentity() async {
    final identity = await AppIdentity.resolve();
    if (identity == null) return;
    _google.androidPackage = identity.androidPackage;
    _google.androidCertSha1 = identity.certSha1;
  }

  /// Composition root — the one place providers and engines are assembled.
  void _wireUp() {
    _mapEngine = MagicLaneMapEngine();
    _routing = MagicLaneRoutingEngine(_mapEngine);
    _navigation = MagicLaneNavigationEngine(_mapEngine);

    _google = GooglePlacesSearchProvider(apiKey: AppConfig.googlePlacesApiKey);

    _search = SearchCoordinator(
      // Google is primary for POI/business/address discovery.
      primary: _google,
      // The on-device engine is the fallback: it answers offline and knows
      // map/navigation targets Google may not.
      fallback: MagicLaneSearchProvider(
        engine: MagicLaneSearchAdapter(_mapEngine),
      ),
      debounce: const Duration(milliseconds: 300),
    );

    _destinations = DestinationController(
      search: _search,
      routing: _routing,
      navigation: _navigation,
    );
  }

  Future<void> _initialiseEngine() async {
    if (!AppConfig.hasMagicLane) {
      setState(() => _initError =
          'Magic Lane API token missing. Build with '
          '--dart-define=MAGICLANE_API_KEY=<token>.');
      return;
    }
    try {
      await _mapEngine.initialize(apiToken: AppConfig.magicLaneApiKey);
      if (mounted) setState(() => _initialised = true);
    } catch (e) {
      if (mounted) setState(() => _initError = 'Map engine failed to start: $e');
    }
  }

  @override
  void dispose() {
    _destinations.dispose();
    _search.dispose();
    _google.dispose();
    _navigation.dispose();
    _mapEngine.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CairoDrive',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF1B6DF3),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFF1B6DF3),
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      home: HomeScreen(
        search: _search,
        destinations: _destinations,
        mapEngine: _mapEngine,
        initError: _initError,
        initialised: _initialised,
      ),
    );
  }
}

/// Map-first home with a prominent search entry point, the way a navigation
/// app is expected to behave.
class HomeScreen extends StatelessWidget {
  const HomeScreen({
    super.key,
    required this.search,
    required this.destinations,
    required this.mapEngine,
    required this.initialised,
    this.initError,
  });

  final SearchCoordinator search;
  final DestinationController destinations;
  final MapEngine mapEngine;
  final bool initialised;
  final String? initError;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(child: _mapArea(context)),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: _searchBar(context),
            ),
          ),
        ],
      ),
    );
  }

  Widget _mapArea(BuildContext context) {
    if (initError != null) {
      return Container(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        alignment: Alignment.center,
        // Scrollable so a long message cannot overflow on a short viewport —
        // e.g. with the keyboard up or on a small screen.
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.map_outlined, size: 48),
            const SizedBox(height: 12),
            Text(initError!, textAlign: TextAlign.center),
          ]),
        ),
      );
    }
    if (!initialised) {
      return const Center(child: CircularProgressIndicator());
    }
    // The live map view is supplied by the engine adapter (GemMap). It is kept
    // out of this widget so the composition root stays engine-agnostic.
    return const MapSurface();
  }

  Widget _searchBar(BuildContext context) {
    return Material(
      elevation: 4,
      borderRadius: BorderRadius.circular(28),
      child: InkWell(
        borderRadius: BorderRadius.circular(28),
        onTap: () => Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => SearchScreen(
            coordinator: search,
            destinations: destinations,
            currentPosition: mapEngine.currentPosition,
          ),
        )),
        child: const Padding(
          padding: EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          child: Row(children: [
            Icon(Icons.search),
            SizedBox(width: 12),
            Text('Where to?', style: TextStyle(fontSize: 16)),
          ]),
        ),
      ),
    );
  }
}

/// Host for the engine's map widget.
///
/// This is the second and last place the SDK is referenced from app code (the
/// other being the adapters file) because a rendered map is necessarily a
/// platform view supplied by the engine. It is isolated in its own widget so
/// swapping engines touches this file and the adapters, nothing else.
class MapSurface extends StatelessWidget {
  const MapSurface({super.key});

  @override
  Widget build(BuildContext context) {
    // GemKit.initialize has already run in _initialiseEngine, so the token is
    // not repeated here — the SDK ignores appAuthorization once initialised.
    return gem.GemMap(
      coordinates: gem.Coordinates(
        latitude: EgyptRegion.cairoCenter.latitude,
        longitude: EgyptRegion.cairoCenter.longitude,
      ),
      zoomLevel: 12,
    );
  }
}
