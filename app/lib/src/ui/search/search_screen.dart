import 'dart:async';

import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:flutter/material.dart';

import '../../navigation/destination_controller.dart';

/// CairoDrive's search screen.
///
/// It renders [SearchResult] only, so it cannot tell whether a row came from
/// Google or from the on-device engine — the fallback banner is the single
/// place provenance is surfaced, and only because the user benefits from
/// knowing results are offline ones.
class SearchScreen extends StatefulWidget {
  const SearchScreen({
    super.key,
    required this.coordinator,
    required this.destinations,
    this.currentPosition,
  });

  final SearchCoordinator coordinator;
  final DestinationController destinations;
  final LatLng? currentPosition;

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  StreamSubscription<SearchState>? _sub;

  SearchState _state = const SearchIdle();
  bool _opening = false;

  @override
  void initState() {
    super.initState();
    _sub = widget.coordinator.states.listen((s) {
      if (mounted) setState(() => _state = s);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _onChanged(String text) {
    widget.coordinator.onQueryChanged(SearchQuery(
      text: text,
      origin: widget.currentPosition,
    ));
  }

  Future<void> _onSelect(SearchResult result) async {
    if (_opening) return;
    setState(() => _opening = true);
    final ok = await widget.destinations.selectResult(result);
    if (!mounted) return;
    setState(() => _opening = false);

    if (!ok) {
      final message = widget.destinations.lastError ?? 'Could not open that place.';
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
      return;
    }
    Navigator.of(context).pop(result);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _controller,
          focusNode: _focus,
          autofocus: true,
          textInputAction: TextInputAction.search,
          // Arabic and English both type naturally; the field follows the text.
          textDirection: null,
          decoration: InputDecoration(
            hintText: 'Search places in Cairo…',
            border: InputBorder.none,
            suffixIcon: _controller.text.isEmpty
                ? null
                : IconButton(
                    icon: const Icon(Icons.clear),
                    onPressed: () {
                      _controller.clear();
                      _onChanged('');
                      setState(() {});
                    },
                  ),
          ),
          onChanged: (t) {
            _onChanged(t);
            setState(() {}); // refresh the clear button
          },
          onSubmitted: (t) => widget.coordinator.submit(
            SearchQuery(text: t, origin: widget.currentPosition),
          ),
        ),
      ),
      body: Stack(
        children: [
          _buildBody(),
          if (_opening)
            const LinearProgressIndicator(minHeight: 3),
        ],
      ),
    );
  }

  Widget _buildBody() {
    return switch (_state) {
      SearchIdle() => _hint('Search for a place, address, or business.'),
      SearchLoading(previous: final previous) => previous.isEmpty
          ? const Center(child: CircularProgressIndicator())
          : Column(children: [
              const LinearProgressIndicator(minHeight: 2),
              Expanded(child: _results(previous, dimmed: true)),
            ]),
      SearchSuccess(isEmpty: true) =>
        _hint('No places matched that search.', icon: Icons.search_off),
      SearchSuccess(
        results: final results,
        usedFallback: final usedFallback,
        fallbackReason: final reason,
      ) =>
        Column(children: [
          if (usedFallback) _fallbackBanner(reason),
          Expanded(child: _results(results)),
        ]),
      SearchError(kind: final kind, message: final message) =>
        _errorView(kind, message),
    };
  }

  Widget _fallbackBanner(SearchFailureKind? reason) {
    final explanation = switch (reason) {
      SearchFailureKind.network => 'You appear to be offline.',
      SearchFailureKind.timeout => 'Online search timed out.',
      SearchFailureKind.quota => 'Online search is temporarily unavailable.',
      SearchFailureKind.auth => 'Online search is unavailable.',
      _ => 'Showing results from the offline map.',
    };
    return Material(
      color: Theme.of(context).colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(children: [
          const Icon(Icons.cloud_off, size: 18),
          const SizedBox(width: 10),
          Expanded(child: Text('$explanation Showing offline map results.')),
        ]),
      ),
    );
  }

  Widget _errorView(SearchFailureKind kind, String message) {
    final headline = switch (kind) {
      SearchFailureKind.network => 'No connection',
      SearchFailureKind.timeout => 'Search timed out',
      SearchFailureKind.quota => 'Search unavailable',
      SearchFailureKind.auth => 'Search is not configured',
      _ => 'Something went wrong',
    };
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.error_outline, size: 40),
          const SizedBox(height: 12),
          Text(headline, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 6),
          Text(message,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 16),
          FilledButton.tonal(
            onPressed: () => widget.coordinator.submit(SearchQuery(
              text: _controller.text,
              origin: widget.currentPosition,
            )),
            child: const Text('Retry'),
          ),
        ]),
      ),
    );
  }

  Widget _hint(String text, {IconData icon = Icons.search}) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 40, color: Theme.of(context).disabledColor),
            const SizedBox(height: 12),
            Text(text,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium),
          ]),
        ),
      );

  Widget _results(List<SearchResult> results, {bool dimmed = false}) {
    return Opacity(
      opacity: dimmed ? 0.5 : 1.0,
      child: ListView.separated(
        itemCount: results.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final r = results[index];
          return ListTile(
            leading: Icon(_iconFor(r.category)),
            title: Text(r.title, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: r.subtitle == null
                ? null
                : Text(r.subtitle!,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: r.distanceMeters == null
                ? null
                : Text(_formatDistance(r.distanceMeters!),
                    style: Theme.of(context).textTheme.labelSmall),
            onTap: dimmed ? null : () => _onSelect(r),
          );
        },
      ),
    );
  }

  static String _formatDistance(double meters) => meters < 1000
      ? '${meters.round()} m'
      : '${(meters / 1000).toStringAsFixed(meters < 10000 ? 1 : 0)} km';

  static IconData _iconFor(String? category) => switch (category) {
        'shopping_mall' || 'store' || 'supermarket' => Icons.storefront,
        'restaurant' || 'cafe' || 'food' => Icons.restaurant,
        'gas_station' => Icons.local_gas_station,
        'hospital' || 'pharmacy' || 'doctor' => Icons.local_hospital,
        'school' || 'university' => Icons.school,
        'lodging' || 'hotel' => Icons.hotel,
        'airport' => Icons.flight,
        'mosque' || 'church' || 'place_of_worship' => Icons.mosque,
        'parking' => Icons.local_parking,
        'street_address' || 'route' => Icons.signpost,
        _ => Icons.place_outlined,
      };
}
