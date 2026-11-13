import 'dart:async';
import 'dart:io';

import 'package:http/http.dart' as http;

class GeminiApiException implements Exception {
  const GeminiApiException(this.statusCode, this.message);

  final int statusCode;
  final String message;

  bool get retryable =>
      statusCode == 408 || statusCode == 429 || statusCode >= 500;

  @override
  String toString() => message;
}

class RetryableGeminiException implements Exception {
  const RetryableGeminiException(this.message);

  final String message;

  @override
  String toString() => message;
}

class GeminiRetryPolicy {
  static const maxAttempts = 3;

  static bool shouldRetry(Object error) {
    if (error is GeminiApiException) return error.retryable;
    return error is RetryableGeminiException ||
        error is SocketException ||
        error is TimeoutException ||
        error is http.ClientException;
  }

  static Future<T> execute<T>(
    Future<T> Function() operation, {
    void Function(int attempt, Object error)? onRetry,
  }) async {
    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return await operation();
      } catch (error) {
        if (attempt == maxAttempts || !shouldRetry(error)) rethrow;
        onRetry?.call(attempt, error);
        await Future<void>.delayed(Duration(seconds: attempt));
      }
    }
    throw StateError('Unreachable Gemini retry state.');
  }
}
