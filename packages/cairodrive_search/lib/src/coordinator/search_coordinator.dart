import 'dart:async';
import 'dart:math' as math;

import '../model/search_query.dart';
import '../model/search_result.dart';
import '../providers/search_provider.dart';
import '../region/egypt.dart';

/// What the UI renders. One state object covers every case the brief lists:
/// idle, loading, results, empty, error, and the fallback notice.
sealed class SearchState {
  const SearchState();
}

/// Nothing typed yet.
class SearchIdle extends SearchState {
  const SearchIdle();
}

/// A request is in flight. [previous] lets the UI keep showing stale rows
/// underneath a spinner instead of flashing empty.
class SearchLoading extends SearchState {
  const SearchLoading({this.previous = const []});
  final List<SearchResult> previous;
}

/// Results are available.
class SearchSuccess extends SearchState {
  const SearchSuccess({
    required this.results,
    required this.servedBy,
    this.usedFallback = false,
    this.fallbackReason,
  });

  final List<SearchResult> results;

  /// Which provider actually answered.
  final SearchProviderId servedBy;

  /// True when the primary provider failed and this came from the fallback.
  final bool usedFallback;

  /// Why the primary failed, for the "showing offline results" banner.
  final SearchFailureKind? fallbackReason;

  bool get isEmpty => results.isEmpty;
}

/// Every provider failed.
class SearchError extends SearchState {
  const SearchError(this.kind, this.message);
  final SearchFailureKind kind;
  final String message;
}

/// Maximum positional lift proximity may grant. Under 2.0 by design.
const double _proximityLiftCap = 1.8;

/// Orchestrates providers: debounce, cancellation, primary → fallback, ranking.
///
/// The UI talks only to this class and only ever sees [SearchResult], so it
/// cannot tell which backend answered.
class SearchCoordinator {
  SearchCoordinator({
    required SearchProvider primary,
    SearchProvider? fallback,
    this.debounce = const Duration(milliseconds: 300),
    this.minQueryLength = 2,
  })  : _primary = primary,
        _fallback = fallback;

  final SearchProvider _primary;
  final SearchProvider? _fallback;

  /// How long the user must pause before a request goes out. Guarantees we do
  /// not issue one request per keystroke.
  final Duration debounce;

  /// Queries shorter than this never hit the network.
  final int minQueryLength;

  final StreamController<SearchState> _states =
      StreamController<SearchState>.broadcast();

  Timer? _debounceTimer;
  int _generation = 0;
  List<SearchResult> _lastResults = const [];

  /// The stream the UI listens to.
  Stream<SearchState> get states => _states.stream;

  /// Counts requests actually dispatched. Used by tests to prove debouncing.
  int dispatchedRequests = 0;

  /// Feed every keystroke here. Only the last one in a [debounce] window runs.
  void onQueryChanged(SearchQuery query) {
    _debounceTimer?.cancel();

    if (query.text.trim().length < minQueryLength) {
      // Cancel in-flight work and drop straight back to idle — an emptied
      // field must never leave a spinner running.
      _generation++;
      _primary.cancelInFlight();
      _fallback?.cancelInFlight();
      _lastResults = const [];
      _emit(const SearchIdle());
      return;
    }

    _emit(SearchLoading(previous: _lastResults));
    _debounceTimer = Timer(debounce, () => _run(query));
  }

  /// Bypass the debounce (user pressed enter / tapped search).
  Future<void> submit(SearchQuery query) {
    _debounceTimer?.cancel();
    if (query.text.trim().isEmpty) {
      _emit(const SearchIdle());
      return Future.value();
    }
    _emit(SearchLoading(previous: _lastResults));
    return _run(query);
  }

  Future<void> _run(SearchQuery query) async {
    final generation = ++_generation;
    // Any request still running belongs to an older query.
    _primary.cancelInFlight();
    _fallback?.cancelInFlight();
    dispatchedRequests++;

    SearchFailureKind? primaryFailure;
    String primaryMessage = '';

    if (_primary.isAvailable) {
      try {
        final results = await _primary.autocomplete(query);
        if (generation != _generation) return; // superseded
        if (results.isNotEmpty) {
          _publish(SearchSuccess(
            results: _rank(results, query),
            servedBy: _primary.id,
          ));
          return;
        }
        // Empty is not an error, but it is a reason to try the fallback —
        // the engine may know a street Google does not.
        primaryFailure = null;
        primaryMessage = 'No results from primary provider';
      } on SearchFailure catch (f) {
        if (generation != _generation) return;
        if (!f.shouldFallBack) return; // cancelled: a newer query owns the UI
        primaryFailure = f.kind;
        primaryMessage = f.message;
      }
    } else {
      primaryFailure = SearchFailureKind.auth;
      primaryMessage = 'Primary provider unavailable';
    }

    final fallback = _fallback;
    if (fallback == null || !fallback.isAvailable) {
      if (generation != _generation) return;
      // Primary returned nothing and there is no usable fallback: that is an
      // empty result, not an error.
      if (primaryFailure == null) {
        _publish(SearchSuccess(results: const [], servedBy: _primary.id));
      } else {
        _emit(SearchError(primaryFailure, primaryMessage));
      }
      return;
    }

    try {
      final results = await fallback.autocomplete(query);
      if (generation != _generation) return;
      _publish(SearchSuccess(
        results: _rank(results, query),
        servedBy: fallback.id,
        usedFallback: true,
        fallbackReason: primaryFailure,
      ));
    } on SearchFailure catch (f) {
      if (generation != _generation) return;
      if (!f.shouldFallBack) return;
      _emit(SearchError(
        primaryFailure ?? f.kind,
        'Search unavailable: $primaryMessage; fallback: ${f.message}',
      ));
    }
  }

  /// Resolve a selection into something navigable.
  ///
  /// For Google this issues the single Place Details (New) call that closes the
  /// session. It is never called before the user actually picks a row.
  Future<SearchResult> select(SearchResult result) {
    final provider = switch (result.provider) {
      SearchProviderId.google => _primary.id == SearchProviderId.google
          ? _primary
          : _fallback,
      _ => _primary.id == result.provider ? _primary : _fallback,
    };
    if (provider == null || provider.id != result.provider) {
      // Nothing can resolve it; if it already has coordinates that is fine.
      if (result.hasLocation) return Future.value(result);
      return Future.error(const SearchFailure(
          SearchFailureKind.malformed, 'No provider available to resolve result'));
    }
    return provider.resolve(result);
  }

  /// Rank without hiding Google's relevance.
  ///
  /// Google's ordering is authoritative, so the base score is the original
  /// index. Greater-Cairo proximity applies a bounded nudge only — enough to
  /// float a nearby branch above a far one, never enough to reorder a clearly
  /// better match below a worse one.
  List<SearchResult> _rank(List<SearchResult> results, SearchQuery query) {
    if (results.length < 2) return results;

    final scored = <(double, int, SearchResult)>[];
    for (final (index, r) in results.indexed) {
      double score = index.toDouble();

      final distance = r.distanceMeters;
      if (distance != null) {
        // Exponential decay over a 3 km scale. Something a few hundred metres
        // away earns close to the full lift; anything past ~10 km earns almost
        // none. The cap is deliberately under 2.0 so proximity can promote a
        // result by at most ~2 positions and can never bury a clearly better
        // match sitting several places above it.
        score -= _proximityLiftCap * math.exp(-distance / 3000.0);
      }

      final loc = r.location;
      if (loc != null && EgyptRegion.isInGreaterCairo(loc)) score -= 0.25;

      scored.add((score, index, r));
    }

    scored.sort((a, b) {
      final c = a.$1.compareTo(b.$1);
      return c != 0 ? c : a.$2.compareTo(b.$2); // stable on ties
    });
    return [for (final s in scored) s.$3];
  }

  void _publish(SearchSuccess state) {
    _lastResults = state.results;
    _emit(state);
  }

  void _emit(SearchState state) {
    if (!_states.isClosed) _states.add(state);
  }

  void dispose() {
    _debounceTimer?.cancel();
    _primary.cancelInFlight();
    _fallback?.cancelInFlight();
    _states.close();
  }
}
