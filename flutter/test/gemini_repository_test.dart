import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jiva_assistance/services/gemini_repository.dart';
import 'package:jiva_assistance/services/gemini_retry_policy.dart';

void main() {
  test('verifies key access to the configured generation model', () async {
    final client = MockClient((request) async {
      expect(request.method, 'GET');
      expect(request.headers['x-goog-api-key'], 'test-key');
      if (request.url.path == '/v1beta/models/gemini-3.8-flash') {
        return http.Response(
          jsonEncode({
            'supportedGenerationMethods': ['generateContent'],
          }),
          200,
        );
      }
      expect(request.url.path, '/v1beta/models/gemini-3.8-live');
      return http.Response(
        jsonEncode({
          'supportedGenerationMethods': ['bidiGenerateContent'],
        }),
        200,
      );
    });
    final repository = GeminiRepository(client: client);

    await expectLater(repository.verifyApiKey('test-key'), completes);
    repository.close();
  });

  test('rejects keys the Gemini API does not accept', () async {
    final repository = GeminiRepository(
      client: MockClient(
        (_) async => http.Response('{"error":"unauthorized"}', 401),
      ),
    );

    await expectLater(
      repository.verifyApiKey('bad-key'),
      throwsA(isA<Exception>()),
    );
    repository.close();
  });

  test('retries transient server errors and rejects non-retryable errors', () async {
    var requests = 0;
    final repository = GeminiRepository(
      client: MockClient((request) async {
        requests++;
        if (requests == 1) return http.Response('temporarily unavailable', 503);
        final supportsLive =
            request.url.path.endsWith(GeminiRepository.liveModel);
        return http.Response(
          jsonEncode({
            'supportedGenerationMethods': [
              supportsLive ? 'bidiGenerateContent' : 'generateContent',
            ],
          }),
          200,
        );
      }),
    );

    await repository.verifyApiKey('test-key');
    expect(requests, 3);
    repository.close();

    var rejectedRequests = 0;
    final rejected = GeminiRepository(
      client: MockClient((_) async {
        rejectedRequests++;
        return http.Response('unauthorized', 401);
      }),
    );
    await expectLater(
      rejected.verifyApiKey('bad-key'),
      throwsA(isA<GeminiApiException>()),
    );
    expect(rejectedRequests, 1);
    rejected.close();
  });
}
