import 'dart:async';

import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:test/test.dart';

/// Scriptable provider so coordinator behaviour can be tested in isolation.
class FakeProvider implements SearchProvider {
  FakeProvider(
    this.id, {
    this.results = const [],
    this.failure,
    this.delay = Duration.zero,
    this.available = true,
  });

  @override
  final SearchProviderId id;

  List<SearchResult> results;
  SearchFailure? failure;
  Duration delay;
  bool available;

  int autocompleteCalls = 0;
  int cancelCalls = 0;
  int resolveCalls = 0;

  @override
  bool get isAvailable => available;

  @override
  void cancelInFlight() => cancelCalls++;

  @override
  Future<List<SearchResult>> autocomplete(SearchQuery query) async {
    autocompleteCalls++;
    if (delay > Duration.zero) await Future<void>.delayed(delay);
    final f = failure;
    if (f != null) throw f;
    return results;
  }

  @override
  Future<SearchResult> resolve(SearchResult result) async {
    resolveCalls++;
    return result.copyWith(
      location: result.location ?? const LatLng(30.0, 31.0),
      needsDetailsLookup: false,
    );
  }
}

SearchResult googleResult(
  String title, {
  String? id,
  double? distance,
  LatLng? location,
  bool pending = true,
}) =>
    SearchResult(
      provider: SearchProviderId.google,
      id: id ?? title,
      placeId: id ?? title,
      title: title,
      location: location,
      distanceMeters: distance,
      needsDetailsLookup: pending,
    );

SearchResult engineResult(String title, {LatLng? location}) => SearchResult(
      provider: SearchProviderId.magicLane,
      id: 'ml:$title',
      title: title,
      location: location ?? const LatLng(30.05, 31.23),
    );

void main() {
  group('Debounce and cancellation', () {
    test('rapid typing dispatches only the final query', () async {
      final google = FakeProvider(SearchProviderId.google,
          results: [googleResult('Cairo Festival City')]);
      final coordinator = SearchCoordinator(
        primary: google,
        debounce: const Duration(milliseconds: 60),
      );
      addTearDown(coordinator.dispose);

      // Simulate someone typing "Cairo" one character at a time.
      for (final q in ['Ca', 'Cai', 'Cair', 'Cairo']) {
        coordinator.onQueryChanged(SearchQuery(text: q));
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
      await Future<void>.delayed(const Duration(milliseconds: 150));

      expect(google.autocompleteCalls, 1,
          reason: '4 keystrokes must collapse into 1 request');
      expect(coordinator.dispatchedRequests, 1);
    });

    test('a pause between words dispatches each settled query', () async {
      final google = FakeProvider(SearchProviderId.google,
          results: [googleResult('X')]);
      final coordinator = SearchCoordinator(
        primary: google,
        debounce: const Duration(milliseconds: 40),
      );
      addTearDown(coordinator.dispose);

      coordinator.onQueryChanged(const SearchQuery(text: 'Mall'));
      await Future<void>.delayed(const Duration(milliseconds: 120));
      coordinator.onQueryChanged(const SearchQuery(text: 'Mall of Egypt'));
      await Future<void>.delayed(const Duration(milliseconds: 120));

      expect(google.autocompleteCalls, 2);
    });

    test('queries below the minimum length never reach the network', () async {
      final google = FakeProvider(SearchProviderId.google);
      final coordinator = SearchCoordinator(
        primary: google,
        debounce: const Duration(milliseconds: 20),
        minQueryLength: 2,
      );
      addTearDown(coordinator.dispose);

      coordinator.onQueryChanged(const SearchQuery(text: 'C'));
      await Future<void>.delayed(const Duration(milliseconds: 80));

      expect(google.autocompleteCalls, 0);
    });

    test('clearing the field returns to idle instead of hanging on loading',
        () async {
      final google = FakeProvider(SearchProviderId.google,
          results: [googleResult('A')]);
      final coordinator = SearchCoordinator(
        primary: google,
        debounce: const Duration(milliseconds: 20),
      );
      addTearDown(coordinator.dispose);

      final states = <SearchState>[];
      coordinator.states.listen(states.add);

      coordinator.onQueryChanged(const SearchQuery(text: 'Cairo'));
      await Future<void>.delayed(const Duration(milliseconds: 60));
      coordinator.onQueryChanged(const SearchQuery(text: ''));
      await Future<void>.delayed(const Duration(milliseconds: 60));

      expect(states.last, isA<SearchIdle>());
      expect(google.cancelCalls, greaterThan(0));
    });

    test('a stale in-flight response cannot overwrite a newer one', () async {
      // Slow provider: the first (slow) query must not clobber the second.
      final google = FakeProvider(
        SearchProviderId.google,
        results: [googleResult('stale')],
        delay: const Duration(milliseconds: 200),
      );
      final coordinator = SearchCoordinator(
        primary: google,
        debounce: const Duration(milliseconds: 10),
      );
      addTearDown(coordinator.dispose);

      final states = <SearchState>[];
      coordinator.states.listen(states.add);

      coordinator.onQueryChanged(const SearchQuery(text: 'first'));
      await Future<void>.delayed(const Duration(milliseconds: 40));
      google.results = [googleResult('fresh')];
      google.delay = Duration.zero;
      coordinator.onQueryChanged(const SearchQuery(text: 'second'));
      await Future<void>.delayed(const Duration(milliseconds: 300));

      final successes = states.whereType<SearchSuccess>().toList();
      expect(successes.last.results.single.title, 'fresh');
    });

    test('a cancelled failure does not trigger the fallback', () async {
      final google = FakeProvider(SearchProviderId.google,
          failure: const SearchFailure(
              SearchFailureKind.cancelled, 'superseded'));
      final engine = FakeProvider(SearchProviderId.magicLane,
          results: [engineResult('should not appear')]);
      final coordinator = SearchCoordinator(
        primary: google,
        fallback: engine,
        debounce: const Duration(milliseconds: 10),
      );
      addTearDown(coordinator.dispose);

      await coordinator.submit(const SearchQuery(text: 'anything'));

      expect(engine.autocompleteCalls, 0);
    });
  });

  group('Fallback behaviour', () {
    Future<SearchState> runWith({
      SearchFailure? googleFailure,
      List<SearchResult> googleResults = const [],
      bool googleAvailable = true,
      List<SearchResult> engineResults = const [],
      SearchFailure? engineFailure,
    }) async {
      final google = FakeProvider(SearchProviderId.google,
          results: googleResults,
          failure: googleFailure,
          available: googleAvailable);
      final engine = FakeProvider(SearchProviderId.magicLane,
          results: engineResults, failure: engineFailure);
      final coordinator = SearchCoordinator(
          primary: google, fallback: engine, debounce: Duration.zero);
      addTearDown(coordinator.dispose);

      final completer = Completer<SearchState>();
      coordinator.states.listen((s) {
        if (s is! SearchLoading && !completer.isCompleted) completer.complete(s);
      });
      await coordinator.submit(const SearchQuery(text: 'Cairo Festival City'));
      return completer.future.timeout(const Duration(seconds: 2));
    }

    test('offline Google falls back to the on-device engine', () async {
      final state = await runWith(
        googleFailure:
            const SearchFailure(SearchFailureKind.network, 'no network'),
        engineResults: [engineResult('Cairo Festival City')],
      );

      expect(state, isA<SearchSuccess>());
      final success = state as SearchSuccess;
      expect(success.usedFallback, isTrue);
      expect(success.servedBy, SearchProviderId.magicLane);
      expect(success.fallbackReason, SearchFailureKind.network);
      expect(success.results.single.title, 'Cairo Festival City');
    });

    test('Google quota error falls back', () async {
      final state = await runWith(
        googleFailure: const SearchFailure(SearchFailureKind.quota, 'over quota'),
        engineResults: [engineResult('fallback hit')],
      );
      expect((state as SearchSuccess).fallbackReason, SearchFailureKind.quota);
    });

    test('Google returning zero results still tries the engine', () async {
      final state = await runWith(
        googleResults: const [],
        engineResults: [engineResult('an obscure Cairo alley')],
      );
      final success = state as SearchSuccess;
      expect(success.usedFallback, isTrue);
      expect(success.servedBy, SearchProviderId.magicLane);
    });

    test('a missing API key falls back rather than erroring', () async {
      final state = await runWith(
        googleAvailable: false,
        engineResults: [engineResult('engine result')],
      );
      expect((state as SearchSuccess).usedFallback, isTrue);
    });

    test('both providers failing surfaces an error state', () async {
      final state = await runWith(
        googleFailure: const SearchFailure(SearchFailureKind.network, 'down'),
        engineFailure:
            const SearchFailure(SearchFailureKind.timeout, 'engine busy'),
      );
      expect(state, isA<SearchError>());
      expect((state as SearchError).kind, SearchFailureKind.network);
    });

    test('genuinely no matches anywhere is empty success, not an error',
        () async {
      final state = await runWith(googleResults: const [], engineResults: const []);
      expect(state, isA<SearchSuccess>());
      expect((state as SearchSuccess).isEmpty, isTrue);
    });

    test('Google succeeding never invokes the fallback', () async {
      final google = FakeProvider(SearchProviderId.google,
          results: [googleResult('Mall of Egypt')]);
      final engine = FakeProvider(SearchProviderId.magicLane);
      final coordinator = SearchCoordinator(
          primary: google, fallback: engine, debounce: Duration.zero);
      addTearDown(coordinator.dispose);

      await coordinator.submit(const SearchQuery(text: 'Mall of Egypt'));

      expect(engine.autocompleteCalls, 0);
    });
  });

  group('Ranking', () {
    test('nearby results are nudged up without discarding relevance order',
        () async {
      final google = FakeProvider(SearchProviderId.google, results: [
        googleResult('Far branch', id: 'far', distance: 40000),
        googleResult('Near branch', id: 'near', distance: 800),
      ]);
      final coordinator =
          SearchCoordinator(primary: google, debounce: Duration.zero);
      addTearDown(coordinator.dispose);

      final completer = Completer<SearchSuccess>();
      coordinator.states.listen((s) {
        if (s is SearchSuccess && !completer.isCompleted) completer.complete(s);
      });
      await coordinator.submit(const SearchQuery(text: 'branch'));
      final success = await completer.future;

      expect(success.results.first.title, 'Near branch');
    });

    test('the proximity nudge is bounded — a far top hit keeps its lead',
        () async {
      // 'Exact' is Google's #1; the nearby alternative is 3 positions down.
      final google = FakeProvider(SearchProviderId.google, results: [
        googleResult('Exact match', id: 'a', distance: 30000),
        googleResult('Other 1', id: 'b', distance: 25000),
        googleResult('Other 2', id: 'c', distance: 20000),
        googleResult('Nearby but weaker', id: 'd', distance: 200),
      ]);
      final coordinator =
          SearchCoordinator(primary: google, debounce: Duration.zero);
      addTearDown(coordinator.dispose);

      final completer = Completer<SearchSuccess>();
      coordinator.states.listen((s) {
        if (s is SearchSuccess && !completer.isCompleted) completer.complete(s);
      });
      await coordinator.submit(const SearchQuery(text: 'match'));
      final success = await completer.future;

      expect(success.results.first.title, 'Exact match',
          reason: 'proximity must not override a clearly better match');
    });

    test('ordering is stable when nothing distinguishes results', () async {
      final google = FakeProvider(SearchProviderId.google, results: [
        googleResult('One', id: '1'),
        googleResult('Two', id: '2'),
        googleResult('Three', id: '3'),
      ]);
      final coordinator =
          SearchCoordinator(primary: google, debounce: Duration.zero);
      addTearDown(coordinator.dispose);

      final completer = Completer<SearchSuccess>();
      coordinator.states.listen((s) {
        if (s is SearchSuccess && !completer.isCompleted) completer.complete(s);
      });
      await coordinator.submit(const SearchQuery(text: 'x'));
      final success = await completer.future;

      expect(success.results.map((r) => r.title), ['One', 'Two', 'Three']);
    });
  });

  group('Selection and destination handoff', () {
    test('selecting a Google result resolves it through Google', () async {
      final google = FakeProvider(SearchProviderId.google);
      final engine = FakeProvider(SearchProviderId.magicLane);
      final coordinator = SearchCoordinator(primary: google, fallback: engine);
      addTearDown(coordinator.dispose);

      final resolved = await coordinator.select(googleResult('CFC', id: 'cfc'));

      expect(google.resolveCalls, 1);
      expect(engine.resolveCalls, 0);
      expect(resolved.hasLocation, isTrue);
    });

    test('selecting an engine result needs no extra lookup', () async {
      final google = FakeProvider(SearchProviderId.google);
      final engine = FakeProvider(SearchProviderId.magicLane);
      final coordinator = SearchCoordinator(primary: google, fallback: engine);
      addTearDown(coordinator.dispose);

      await coordinator.select(engineResult('Tahrir Square'));

      expect(google.resolveCalls, 0);
      expect(engine.resolveCalls, 1);
    });

    test('a resolved result converts into a Destination', () {
      final resolved = googleResult(
        'Cairo Festival City Mall',
        id: 'cfc',
        location: const LatLng(30.0286, 31.4090),
        pending: false,
      );

      final destination = Destination.fromSearchResult(resolved);

      expect(destination.name, 'Cairo Festival City Mall');
      expect(destination.latitude, closeTo(30.0286, 0.0001));
      expect(destination.longitude, closeTo(31.4090, 0.0001));
      expect(destination.sourceProvider, SearchProviderId.google);
      expect(destination.sourcePlaceId, 'cfc');
    });

    test('an unresolved result is refused as a destination', () {
      expect(
        () => Destination.fromSearchResult(googleResult('unresolved')),
        throwsA(isA<SearchFailure>()),
      );
    });
  });

  group('Provider opacity', () {
    test('results from either provider are the same type to the UI', () async {
      final fromGoogle = googleResult('G',
          location: const LatLng(30.0, 31.0), pending: false);
      final fromEngine = engineResult('M');

      // The UI reads exactly these fields, regardless of origin.
      for (final r in [fromGoogle, fromEngine]) {
        expect(r, isA<SearchResult>());
        expect(r.title, isNotEmpty);
        expect(r.hasLocation, isTrue);
      }
    });
  });
}
