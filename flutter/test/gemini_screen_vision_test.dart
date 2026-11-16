import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jiva_assistance/services/gemini_repository.dart';

void main() {
  test(
    'explicit screen analysis sends only the chosen JPEG and question',
    () async {
      var requests = 0;
      final repository = GeminiRepository(
        client: MockClient((request) async {
          requests++;
          expect(request.url.path, contains(':generateContent'));
          expect(request.headers['x-goog-api-key'], 'test-key');
          expect(request.url.queryParameters.containsKey('key'), isFalse);
          final data = jsonDecode(request.body) as Map<String, dynamic>;
          final parts = ((data['contents'] as List).single['parts'] as List);
          expect(parts[0]['text'], 'What is visible?');
          expect(parts[1]['inlineData']['mimeType'], 'image/jpeg');
          expect(parts[1]['inlineData']['data'], base64Encode([1, 2, 3]));
          return http.Response(
            jsonEncode({
              'candidates': [
                {
                  'content': {
                    'parts': [
                      {'text': 'Two buttons visible.'},
                    ],
                  },
                },
              ],
            }),
            200,
          );
        }),
      );
      expect(
        await repository.explainReviewedScreen('test-key', [
          1,
          2,
          3,
        ], 'What is visible?'),
        'Two buttons visible.',
      );
      expect(requests, 1);
      repository.close();
    },
  );

  test('invalid screenshot and empty key fail before any upload', () async {
    var requests = 0;
    final repository = GeminiRepository(
      client: MockClient((request) async {
        requests++;
        return http.Response('{}', 200);
      }),
    );
    await expectLater(
      repository.explainReviewedScreen('', [1], 'Explain'),
      throwsA(isA<StateError>()),
    );
    await expectLater(
      repository.explainReviewedScreen('key', [], 'Explain'),
      throwsArgumentError,
    );
    await expectLater(
      repository.explainReviewedScreen('key', [1], ''),
      throwsArgumentError,
    );
    expect(requests, 0);
    repository.close();
  });
}
