import 'dart:convert';

import 'package:http/http.dart' as http;

/// Tokens must come from a signed-in Firebase Auth session and App Check.
/// Never pass a Gemini API key or embed static credentials in a mobile build.
class JivaGatewayTokens {
  const JivaGatewayTokens({
    required this.firebaseIdToken,
    required this.appCheckToken,
  });

  final String firebaseIdToken;
  final String appCheckToken;
}

typedef JivaGatewayTokenProvider = Future<JivaGatewayTokens> Function();

class JivaGatewayClient {
  JivaGatewayClient({
    required Uri baseUrl,
    required JivaGatewayTokenProvider tokens,
    http.Client? client,
  })  : _baseUrl = baseUrl,
        _tokens = tokens,
        _client = client ?? http.Client() {
    if (baseUrl.scheme != 'https' ||
        baseUrl.host.isEmpty ||
        baseUrl.userInfo.isNotEmpty ||
        baseUrl.hasQuery ||
        baseUrl.hasFragment) {
      throw ArgumentError('A trusted HTTPS gateway base URL is required.');
    }
  }

  final Uri _baseUrl;
  final JivaGatewayTokenProvider _tokens;
  final http.Client _client;

  Future<Map<String, dynamic>> interpret(String text) async {
    if (text.trim().isEmpty || text.length > 2000) {
      throw ArgumentError('Text must be 1–2000 characters.');
    }
    return _post('v1/interpret', {'text': text});
  }

  Future<String> createLiveToken() async {
    final result = await _post('v1/live-token', const {});
    final token = result['token'];
    if (token is! String || token.isEmpty) {
      throw const FormatException('Gateway returned no Live token.');
    }
    return token;
  }

  Future<Map<String, dynamic>> _post(
    String path,
    Map<String, dynamic> payload,
  ) async {
    final session = await _tokens();
    if (session.firebaseIdToken.isEmpty || session.appCheckToken.isEmpty) {
      throw StateError('Sign in and complete App Check before using Jiva.');
    }
    final uri = _baseUrl.resolve(path);
    if (uri.scheme != 'https' || uri.host != _baseUrl.host) {
      throw StateError('Unsafe gateway URL.');
    }
    // Never automatically redirect authenticated requests to another host.
    final request = http.Request('POST', uri)
      ..followRedirects = false
      ..headers.addAll({
        'Authorization': 'Bearer ${session.firebaseIdToken}',
        'X-Firebase-AppCheck': session.appCheckToken,
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      })
      ..body = jsonEncode(payload);
    final stream = await _client.send(request).timeout(const Duration(seconds: 30));
    final response = await http.Response.fromStream(stream)
        .timeout(const Duration(seconds: 30));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError('Jiva gateway failed (HTTP ${response.statusCode}).');
    }
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Invalid gateway response.');
    }
    return decoded;
  }

  void close() => _client.close();
}
