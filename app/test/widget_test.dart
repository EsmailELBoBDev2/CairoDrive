import 'package:cairodrive/main.dart';
import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:cairodrive/src/engine/engine_ports.dart';
import 'package:cairodrive/src/navigation/destination_controller.dart';

/// Widget-level checks that need no engine, no network and no API keys.
///
/// The engine-backed paths are deliberately not exercised here — they need a
/// device. The search logic itself is covered exhaustively by the pure-Dart
/// suite in packages/cairodrive_search.
void main() {
  testWidgets('home shows the search entry point', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: HomeScreen(
        search: SearchCoordinator(primary: _OfflineProvider()),
        destinations: _NullDestinationController(),
        mapEngine: _StubMapEngine(),
        initialised: false,
      ),
    ));

    expect(find.text('Where to?'), findsOneWidget);
  });

  testWidgets('a missing map token is surfaced instead of a blank screen',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: HomeScreen(
        search: SearchCoordinator(primary: _OfflineProvider()),
        destinations: _NullDestinationController(),
        mapEngine: _StubMapEngine(),
        initialised: false,
        initError: 'Magic Lane API token missing.',
      ),
    ));

    expect(find.textContaining('token missing'), findsOneWidget);
  });
}

class _StubMapEngine implements MapEngine {
  @override
  bool get isReady => false;
  @override
  LatLng? get currentPosition => null;
  @override
  Future<void> initialize({required String apiToken}) async {}
  @override
  Future<void> dispose() async {}
}

class _OfflineProvider implements SearchProvider {
  @override
  SearchProviderId get id => SearchProviderId.magicLane;
  @override
  bool get isAvailable => false;
  @override
  Future<List<SearchResult>> autocomplete(SearchQuery query) async => const [];
  @override
  Future<SearchResult> resolve(SearchResult result) async => result;
  @override
  void cancelInFlight() {}
}

class _NullDestinationController implements DestinationController {
  @override
  dynamic noSuchMethod(Invocation invocation) => null;
}
