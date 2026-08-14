import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:cairodrive_search/cairodrive_search.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:test/test.dart';

/// Autocomplete payload shaped exactly like Places API (New) returns.
String autocompleteBody(List<Map<String, dynamic>> predictions) => jsonEncode({
      'suggestions': [
        for (final p in predictions) {'placePrediction': p}
      ]
    });

Map<String, dynamic> prediction({
  required String placeId,
  required String main,
  String? secondary,
  List<String> types = const ['point_of_interest'],
  num? distance,
}) =>
    {
      'placeId': placeId,
      'text': {'text': secondary == null ? main : '$main, $secondary'},
      'structuredFormat': {
        'mainText': {'text': main},
        if (secondary != null) 'secondaryText': {'text': secondary},
      },
      'types': types,
      if (distance != null) 'distanceMeters': distance,
    };

String detailsBody({
  required String id,
  required String name,
  required double lat,
  required double lng,
  String? address,
  String? primaryType,
}) =>
    jsonEncode({
      'id': id,
      'displayName': {'text': name},
      if (address != null) 'formattedAddress': address,
      'location': {'latitude': lat, 'longitude': lng},
      if (primaryType != null) 'primaryType': primaryType,
    });

void main() {
  group('Google Places (New) — request construction', () {
    test('sends explicit field mask, never a wildcard', () async {
      String? mask;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'test-key',
        client: MockClient((req) async {
          mask = req.headers['X-Goog-FieldMask'];
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: 'Cairo Festival City'));

      expect(mask, isNotNull);
      expect(mask, isNot(contains('*')));
      expect(mask, contains('suggestions.placePrediction.placeId'));
    });

    test('authenticates with X-Goog-Api-Key header, not a query parameter',
        () async {
      late http.Request captured;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'secret-key',
        client: MockClient((req) async {
          captured = req;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: 'Mall of Egypt'));

      expect(captured.headers['X-Goog-Api-Key'], 'secret-key');
      expect(captured.url.query, isNot(contains('secret-key')));
    });

    test('biases to Egypt and greater Cairo when no device fix is available',
        () async {
      late Map<String, dynamic> body;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          body = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: 'City Stars'));

      expect(body['regionCode'], 'EG');
      final centre = body['locationBias']['circle']['center'];
      expect(centre['latitude'], closeTo(30.0444, 0.001));
      expect(centre['longitude'], closeTo(31.2357, 0.001));
      // No origin means no distanceMeters can be requested.
      expect(body.containsKey('origin'), isFalse);
    });

    test('uses the device position for bias and origin when available',
        () async {
      late Map<String, dynamic> body;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          body = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(
        text: 'pharmacy',
        origin: LatLng(30.0131, 31.2089), // Giza
      ));

      expect(body['origin']['latitude'], closeTo(30.0131, 0.0001));
      expect(body['locationBias']['circle']['center']['longitude'],
          closeTo(31.2089, 0.0001));
    });
  });

  group('Language handling (Arabic / English)', () {
    test('infers ar for an Arabic business name', () async {
      late Map<String, dynamic> body;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          body = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: 'مطعم أبو السيد'));
      expect(body['languageCode'], 'ar');
    });

    test('infers ar for an Arabic street address', () async {
      late Map<String, dynamic> body;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          body = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: 'شارع التحرير، الدقي'));
      expect(body['languageCode'], 'ar');
    });

    test('infers en for a Latin-script address', () async {
      late Map<String, dynamic> body;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          body = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: '90th Street, New Cairo'));
      expect(body['languageCode'], 'en');
    });

    test('treats a mixed Arabic/Latin query as Arabic', () async {
      late Map<String, dynamic> body;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          body = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      await provider.autocomplete(const SearchQuery(text: 'كافيه Starbucks'));
      expect(body['languageCode'], 'ar');
    });
  });

  group('Session token lifecycle', () {
    test('reuses one token across a typing session', () async {
      final tokens = <String>[];
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          final body = jsonDecode(req.body);
          tokens.add(body['sessionToken'] as String);
          return http.Response(autocompleteBody([]), 200);
        }),
      );

      for (final q in ['Cai', 'Cairo', 'Cairo Fes', 'Cairo Festival']) {
        await provider.autocomplete(SearchQuery(text: q));
      }

      expect(tokens, hasLength(4));
      expect(tokens.toSet(), hasLength(1), reason: 'one session, one token');
    });

    test('sends the session token on Place Details and then retires it',
        () async {
      String? detailsToken;
      String? firstToken;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          if (req.url.path.contains('places:autocomplete')) {
            firstToken ??=
                jsonDecode(req.body)['sessionToken'] as String;
            return http.Response(
                autocompleteBody([prediction(placeId: 'p1', main: 'CFC')]), 200);
          }
          detailsToken = req.url.queryParameters['sessionToken'];
          return http.Response(
              detailsBody(id: 'p1', name: 'CFC', lat: 30.03, lng: 31.41), 200);
        }),
      );

      final results =
          await provider.autocomplete(const SearchQuery(text: 'Cairo Festival'));
      await provider.resolve(results.single);

      expect(detailsToken, isNotNull);
      expect(detailsToken, firstToken,
          reason: 'Details must close the same billing session');
      expect(provider.debugSessionToken, isNull,
          reason: 'token must not be reused after Details');
    });

    test('starts a fresh token for the next session after a selection',
        () async {
      final autocompleteTokens = <String>[];
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          if (req.url.path.contains('places:autocomplete')) {
            autocompleteTokens.add(
                jsonDecode(req.body)['sessionToken'] as String);
            return http.Response(
                autocompleteBody([prediction(placeId: 'p1', main: 'X')]), 200);
          }
          return http.Response(
              detailsBody(id: 'p1', name: 'X', lat: 30.0, lng: 31.0), 200);
        }),
      );

      final first = await provider.autocomplete(const SearchQuery(text: 'aaa'));
      await provider.resolve(first.single);
      await provider.autocomplete(const SearchQuery(text: 'bbb'));

      expect(autocompleteTokens, hasLength(2));
      expect(autocompleteTokens[0], isNot(autocompleteTokens[1]),
          reason: 'a new search session needs a new token');
    });

    test('mints RFC-4122 v4 tokens', () {
      final token = SessionTokenFactory().newToken();
      expect(
        token,
        matches(RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')),
      );
    });
  });

  group('Place Details is deferred until selection', () {
    test('autocomplete alone never calls the details endpoint', () async {
      final paths = <String>[];
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          paths.add(req.url.path);
          return http.Response(
              autocompleteBody([
                prediction(placeId: 'p1', main: 'A'),
                prediction(placeId: 'p2', main: 'B'),
              ]),
              200);
        }),
      );

      final results = await provider.autocomplete(const SearchQuery(text: 'test'));

      expect(results, hasLength(2));
      expect(paths.where((p) => p.contains('places:autocomplete')), hasLength(1));
      expect(paths.any((p) => RegExp(r'/v1/places/[^:]+$').hasMatch(p)), isFalse);
      expect(results.every((r) => r.needsDetailsLookup), isTrue);
      expect(results.every((r) => r.location == null), isTrue);
    });

    test('details uses the minimal navigation field mask', () async {
      String? mask;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          if (req.url.path.contains('places:autocomplete')) {
            return http.Response(
                autocompleteBody([prediction(placeId: 'p1', main: 'A')]), 200);
          }
          mask = req.headers['X-Goog-FieldMask'];
          return http.Response(
              detailsBody(id: 'p1', name: 'A', lat: 30.0, lng: 31.0), 200);
        }),
      );

      final r = await provider.autocomplete(const SearchQuery(text: 'a'));
      await provider.resolve(r.single);

      expect(mask, 'id,displayName,formattedAddress,location,primaryType');
      expect(mask, isNot(contains('*')));
    });

    test('resolve fills coordinates and clears the pending flag', () async {
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((req) async {
          if (req.url.path.contains('places:autocomplete')) {
            return http.Response(
                autocompleteBody([
                  prediction(
                      placeId: 'cfc',
                      main: 'Cairo Festival City Mall',
                      secondary: 'New Cairo'),
                ]),
                200);
          }
          return http.Response(
              detailsBody(
                id: 'cfc',
                name: 'Cairo Festival City Mall',
                lat: 30.0286,
                lng: 31.4090,
                address: 'Ring Rd, New Cairo, Cairo Governorate',
                primaryType: 'shopping_mall',
              ),
              200);
        }),
      );

      final raw =
          await provider.autocomplete(const SearchQuery(text: 'Cairo Festival'));
      final resolved = await provider.resolve(raw.single);

      expect(resolved.needsDetailsLookup, isFalse);
      expect(resolved.latitude, closeTo(30.0286, 0.0001));
      expect(resolved.longitude, closeTo(31.4090, 0.0001));
      expect(resolved.category, 'shopping_mall');
      expect(resolved.subtitle, contains('New Cairo'));
    });
  });

  group('Failure taxonomy', () {
    Future<SearchFailure> failureFrom(http.Client client) async {
      final provider = GooglePlacesSearchProvider(apiKey: 'k', client: client);
      try {
        await provider.autocomplete(const SearchQuery(text: 'query'));
        fail('expected a SearchFailure');
      } on SearchFailure catch (f) {
        return f;
      }
    }

    test('offline / airplane mode maps to network', () async {
      final f = await failureFrom(MockClient(
          (_) async => throw const SocketException('Network is unreachable')));
      expect(f.kind, SearchFailureKind.network);
      expect(f.shouldFallBack, isTrue);
    });

    test('slow response maps to timeout', () async {
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        timeout: const Duration(milliseconds: 50),
        client: MockClient((_) async {
          await Future<void>.delayed(const Duration(milliseconds: 500));
          return http.Response(autocompleteBody([]), 200);
        }),
      );
      await expectLater(
        provider.autocomplete(const SearchQuery(text: 'slow')),
        throwsA(isA<SearchFailure>()
            .having((f) => f.kind, 'kind', SearchFailureKind.timeout)),
      );
    });

    test('HTTP 429 maps to quota', () async {
      final f = await failureFrom(
          MockClient((_) async => http.Response('rate limited', 429)));
      expect(f.kind, SearchFailureKind.quota);
    });

    test('HTTP 403 maps to auth', () async {
      final f = await failureFrom(
          MockClient((_) async => http.Response('forbidden', 403)));
      expect(f.kind, SearchFailureKind.auth);
    });

    test('HTTP 500 maps to http', () async {
      final f = await failureFrom(
          MockClient((_) async => http.Response('boom', 500)));
      expect(f.kind, SearchFailureKind.http);
      expect(f.statusCode, 500);
    });

    test('non-JSON body maps to malformed', () async {
      final f = await failureFrom(
          MockClient((_) async => http.Response('<html>nope</html>', 200)));
      expect(f.kind, SearchFailureKind.malformed);
    });

    test('a missing key fails fast without a network call', () async {
      var called = false;
      final provider = GooglePlacesSearchProvider(
        apiKey: '',
        client: MockClient((_) async {
          called = true;
          return http.Response(autocompleteBody([]), 200);
        }),
      );
      await expectLater(
        provider.autocomplete(const SearchQuery(text: 'x')),
        throwsA(isA<SearchFailure>()
            .having((f) => f.kind, 'kind', SearchFailureKind.auth)),
      );
      expect(called, isFalse);
      expect(provider.isAvailable, isFalse);
    });
  });

  group('Response tolerance', () {
    test('an empty query short-circuits with no request', () async {
      var called = false;
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((_) async {
          called = true;
          return http.Response(autocompleteBody([]), 200);
        }),
      );
      expect(await provider.autocomplete(const SearchQuery(text: '   ')), isEmpty);
      expect(called, isFalse);
    });

    test('a response with no suggestions key is empty, not an error', () async {
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((_) async => http.Response('{}', 200)),
      );
      expect(await provider.autocomplete(const SearchQuery(text: 'zzz')), isEmpty);
    });

    test('skips queryPrediction rows and predictions without a placeId',
        () async {
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        client: MockClient((_) async => http.Response(
              jsonEncode({
                'suggestions': [
                  {
                    'queryPrediction': {
                      'text': {'text': 'pizza near me'}
                    }
                  },
                  {
                    'placePrediction': {
                      'text': {'text': 'no id here'}
                    }
                  },
                  {'placePrediction': prediction(placeId: 'good', main: 'Real')},
                ]
              }),
              200,
            )),
      );

      final results = await provider.autocomplete(const SearchQuery(text: 'p'));
      expect(results, hasLength(1));
      expect(results.single.placeId, 'good');
    });

    test('caps the number of suggestions returned', () async {
      final provider = GooglePlacesSearchProvider(
        apiKey: 'k',
        maxSuggestions: 3,
        client: MockClient((_) async => http.Response(
              autocompleteBody([
                for (var i = 0; i < 10; i++)
                  prediction(placeId: 'p$i', main: 'Place $i')
              ]),
              200,
            )),
      );
      expect(await provider.autocomplete(const SearchQuery(text: 'p')),
          hasLength(3));
    });
  });
}
