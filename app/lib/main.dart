import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:flutter/material.dart';

import 'src/config/app_config.dart';
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
        child: Padding(
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

/// Placeholder host for the engine's map widget.
///
/// Kept as its own widget so swapping the map engine touches one file.
class MapSurface extends StatelessWidget {
  const MapSurface({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      alignment: Alignment.center,
      child: const Text('Map'),
    );
  }
}
