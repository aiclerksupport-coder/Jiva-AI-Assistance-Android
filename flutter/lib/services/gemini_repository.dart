import 'dart:convert';

import 'package:http/http.dart' as http;

import 'gemini_retry_policy.dart';

class GeminiRepository {
  GeminiRepository({http.Client? client})
      : _client = client ?? http.Client();

  static const model = 'gemini-3.8-flash';
  static const liveModel = 'gemini-3.8-live';
  final http.Client _client;
  var _requestGeneration = 0;

  Future<Map<String, dynamic>> interpret(
    String apiKey,
    String userText, {
    void Function(int attempt, Object error)? onRetry,
  }) =>
      _executeWithRetry(() async {
        if (apiKey.trim().isEmpty) {
          throw ArgumentError('Add your Gemini API key in Settings.');
        }
        final system = 'You are Jiva, an assistant. Understand the user language '
            'and reply in that language. Return JSON with reply, action, appName, '
            'recipientQuery, message, settingsTarget, and phoneAction. action must '
            'be one of CHAT, OPEN_APP, CALL, SMS, WHATSAPP_SHARE, REMINDER, '
            'OPEN_SETTINGS, CAMERA, PHONE_NAVIGATION. settingsTarget must be '
            'general, wifi, bluetooth, display, or sound. phoneAction must be '
            'home, back, recents, notifications, or quick_settings. Use '
            'PHONE_NAVIGATION only for an explicit request. Never claim an action '
            'was completed. If unclear use CHAT and ask a question. Actions are '
            'validated and require user approval.';
        final uri = Uri.https(
          'generativelanguage.googleapis.com',
          '/v1beta/models/$model:generateContent',
        );
        final response = await _client
            .post(
              uri,
              headers: {
                'Content-Type': 'application/json; charset=utf-8',
                'x-goog-api-key': apiKey,
              },
              body: jsonEncode({
                'systemInstruction': {
                  'parts': [
                    {'text': system}
                  ]
                },
                'contents': [
                  {
                    'role': 'user',
                    'parts': [
                      {'text': userText}
                    ]
                  }
                ],
                'generationConfig': {'responseMimeType': 'application/json'},
              }),
            )
            .timeout(const Duration(seconds: 25));
        if (response.statusCode < 200 || response.statusCode >= 300) {
          throw GeminiApiException(
            response.statusCode,
            'Gemini request failed (HTTP ${response.statusCode}). '
                'Check key, model, quota, and network.',
          );
        }
        final decoded = jsonDecode(response.body) as Map<String, dynamic>;
        final candidates = decoded['candidates'] as List<dynamic>?;
        if (candidates == null || candidates.isEmpty) {
          throw const FormatException('Gemini returned no response.');
        }
        final content = candidates.first['content'] as Map<String, dynamic>?;
        final parts = content?['parts'] as List<dynamic>?;
        if (parts == null || parts.isEmpty || parts.first['text'] is! String) {
          throw const FormatException('Gemini returned an invalid response.');
        }
        final result = jsonDecode(parts.first['text'] as String);
        if (result is! Map<String, dynamic>) {
          throw const FormatException('Gemini returned an invalid action plan.');
        }
        return result;
      }, onRetry: onRetry);

  Future<void> verifyApiKey(
    String apiKey, {
    void Function(int attempt, Object error)? onRetry,
  }) =>
      _executeWithRetry(() async {
        if (apiKey.trim().isEmpty) {
          throw ArgumentError('Enter your Gemini API key.');
        }
        for (final (modelName, requiredMethod) in [
          (model, 'generateContent'),
          (liveModel, 'bidiGenerateContent'),
        ]) {
          final response = await _client
              .get(
                Uri.https(
                  'generativelanguage.googleapis.com',
                  '/v1beta/models/$modelName',
                ),
                headers: {'x-goog-api-key': apiKey},
              )
              .timeout(const Duration(seconds: 15));
          if (response.statusCode < 200 || response.statusCode >= 300) {
            throw GeminiApiException(
              response.statusCode,
              'Gemini model check failed for $modelName (HTTP ${response.statusCode}). '
                  'Verify the key, API access, and network.',
            );
          }
          final decoded = jsonDecode(response.body) as Map<String, dynamic>;
          final methods = decoded['supportedGenerationMethods'];
          if (methods is! List || !methods.contains(requiredMethod)) {
            throw FormatException(
              '$modelName does not support $requiredMethod for Jiva.',
            );
          }
        }
      }, onRetry: onRetry);

  Future<T> _executeWithRetry<T>(
    Future<T> Function() operation, {
    void Function(int attempt, Object error)? onRetry,
  }) {
    final generation = _requestGeneration;
    return GeminiRetryPolicy.execute(() async {
      if (generation != _requestGeneration) {
        throw StateError('Gemini request cancelled.');
      }
      return operation();
    }, onRetry: (attempt, error) {
      if (generation != _requestGeneration) {
        throw StateError('Gemini request cancelled.');
      }
      onRetry?.call(attempt, error);
    });
  }

  void close() {
    _requestGeneration++;
    _client.close();
  }
}
