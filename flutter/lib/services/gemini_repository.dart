import 'dart:convert';

import 'package:http/http.dart' as http;

import 'gemini_retry_policy.dart';
import '../domain/conversation_context.dart';

class GeminiRepository {
  GeminiRepository({http.Client? client}) : _client = client ?? http.Client();

  static const model = 'gemini-3.8-flash';
  static const liveModel = 'gemini-3.8-live';
  final http.Client _client;
  var _requestGeneration = 0;
  final conversation = ConversationContext();

  Future<Map<String, dynamic>> interpret(
    String apiKey,
    String userText, {
    void Function(int attempt, Object error)? onRetry,
  }) => _executeWithRetry(() async {
    if (apiKey.trim().isEmpty) {
      throw ArgumentError('Add your Gemini API key in Settings.');
    }
    final system =
        'You are Jiva, an assistant. Understand the user language '
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
                {'text': system},
              ],
            },
            'contents': conversation.contentsWithUserTurn(userText),
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
    final reply = result['reply'];
    if (reply is String && reply.trim().isNotEmpty) {
      conversation.appendCompletedTurn(userText, reply);
    }
    return result;
  }, onRetry: onRetry);

  /// Planning only. The model cannot invoke device APIs; the returned allowlist
  /// is parsed and approved by the Flutter workflow controller.
  Future<Map<String, dynamic>> planPhoneWorkflow(
    String apiKey,
    String task, {
    void Function(int attempt, Object error)? onRetry,
  }) => _executeWithRetry(() async {
    if (apiKey.trim().isEmpty || task.trim().isEmpty || task.length > 1000) {
      throw ArgumentError(
        'Provide a short task and a verified Gemini API key.',
      );
    }
    final response = await _client
        .post(
          Uri.https(
            'generativelanguage.googleapis.com',
            '/v1beta/models/$model:generateContent',
          ),
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'x-goog-api-key': apiKey,
          },
          body: jsonEncode({
            'systemInstruction': {
              'parts': [
                {
                  'text':
                      'Create a conservative Android phone workflow only. Return JSON: '
                      '{"steps":[{"action":"read|click|type|scroll_forward|scroll_backward|back|open_app|verify_text", "label":""}],"note":""}. '
                      'Maximum 8 steps. For click, label is the exact visible label. For type, label contains at most 100 characters of non-sensitive text. '
                      'Avoid login, password, OTP, finance, banking, payment, purchase, or irreversible actions. '
                      'open_app is allowed only for a user-named installed app (label = exact app name); never guess a package. '
                      'For plans with open_app, the LAST step MUST be verify_text with an exact observable success phrase the user can review. '
                      'If the result cannot be observed, do not use open_app. No private screens, credentials, payments or irreversible actions. '
                      'The user approves the plan; app transitions need separate approval. '
                      'Do not claim completion or screen visibility. If uncertain return one read step.',
                },
              ],
            },
            'contents': [
              {
                'role': 'user',
                'parts': [
                  {'text': task},
                ],
              },
            ],
            'generationConfig': {'responseMimeType': 'application/json'},
          }),
        )
        .timeout(const Duration(seconds: 25));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw GeminiApiException(
        response.statusCode,
        'AI planner unavailable (HTTP ${response.statusCode}).',
      );
    }
    final decoded = jsonDecode(response.body) as Map<String, dynamic>;
    final candidates = decoded['candidates'];
    if (candidates is! List || candidates.isEmpty) {
      throw const FormatException('AI planner returned no candidates.');
    }
    final content = candidates.first['content'];
    if (content is! Map ||
        content['parts'] is! List ||
        (content['parts'] as List).isEmpty) {
      throw const FormatException('AI planner returned invalid content.');
    }
    final first = (content['parts'] as List).first;
    if (first is! Map || first['text'] is! String) {
      throw const FormatException('AI planner returned invalid JSON.');
    }
    final plan = jsonDecode(first['text'] as String);
    if (plan is! Map<String, dynamic>) {
      throw const FormatException('Invalid plan.');
    }
    return plan;
  }, onRetry: onRetry);

  /// User-consented single-image analysis. No device actions, no auto-upload,
  /// no screenshot persistence, and no API key exposed in a URL.
  Future<String> explainReviewedScreen(
    String apiKey,
    List<int> jpeg,
    String question,
  ) async {
    if (apiKey.trim().isEmpty) {
      throw StateError('Add a verified Gemini key before sharing an image.');
    }
    if (jpeg.isEmpty || jpeg.length > 600000) {
      throw ArgumentError('Screenshot must be between 1 and 600000 bytes.');
    }
    if (question.trim().isEmpty || question.length > 500) {
      throw ArgumentError('Question must be 1-500 characters.');
    }
    final response = await _client
        .post(
          Uri.https(
            'generativelanguage.googleapis.com',
            '/v1beta/models/$model:generateContent',
          ),
          headers: {
            'Content-Type': 'application/json',
            'x-goog-api-key': apiKey,
          },
          body: jsonEncode({
            'systemInstruction': {
              'parts': [
                {
                  'text':
                      'You analyze a single user-approved screenshot, not a live phone screen. '
                      'Explain what is visible concisely, in the user language. '
                      'Do not claim to have tapped, submitted, saved, or verified any action. '
                      'Do not request passwords, OTPs, payment details, or credentials. '
                      'If anything is unclear, explicitly say so.',
                },
              ],
            },
            'contents': [
              {
                'role': 'user',
                'parts': [
                  {'text': question.trim()},
                  {
                    'inlineData': {
                      'mimeType': 'image/jpeg',
                      'data': base64Encode(jpeg),
                    },
                  },
                ],
              },
            ],
          }),
        )
        .timeout(const Duration(seconds: 30));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw GeminiApiException(
        response.statusCode,
        'Visual analysis failed (HTTP ${response.statusCode}).',
      );
    }
    final payload = jsonDecode(response.body);
    if (payload is! Map ||
        payload['candidates'] is! List ||
        (payload['candidates'] as List).isEmpty) {
      throw const FormatException('AI returned no visual explanation.');
    }
    final candidate = (payload['candidates'] as List).first;
    if (candidate is! Map || candidate['content'] is! Map) {
      throw const FormatException('Invalid visual explanation.');
    }
    final parts = (candidate['content'] as Map)['parts'];
    if (parts is! List) {
      throw const FormatException('No visual explanation text.');
    }
    final answer = parts
        .whereType<Map>()
        .map((e) => e['text'])
        .whereType<String>()
        .join(' ')
        .trim();
    if (answer.isEmpty) {
      throw const FormatException('Visual answer was empty.');
    }
    return answer.length > 6000 ? answer.substring(0, 6000) : answer;
  }

  Future<void> verifyApiKey(
    String apiKey, {
    void Function(int attempt, Object error)? onRetry,
  }) => _executeWithRetry(() async {
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
    return GeminiRetryPolicy.execute(
      () async {
        if (generation != _requestGeneration) {
          throw StateError('Gemini request cancelled.');
        }
        return operation();
      },
      onRetry: (attempt, error) {
        if (generation != _requestGeneration) {
          throw StateError('Gemini request cancelled.');
        }
        onRetry?.call(attempt, error);
      },
    );
  }

  void close() {
    _requestGeneration++;
    _client.close();
  }
}
