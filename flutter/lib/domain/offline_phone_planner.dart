import 'phone_workflow.dart';

/// Tiny deterministic offline fallback, NOT a local LLM or autonomous vision.
/// Only explicit read, back and scroll directions are accepted. No clicks.
class OfflinePhonePlanner {
  const OfflinePhonePlanner();

  PhoneWorkflow? plan(String text) {
    final value = text.trim().toLowerCase();
    if (value.isEmpty || value.length > 200) return null;
    final tokens = value.split(RegExp(r'\s+'));
    if (tokens.any(
      (token) => RegExp(
        r'^(pay|purchase|login|otp|password|send|delete|transfer|submit)$',
      ).hasMatch(token),
    )) {
      return null;
    }
    if (RegExp(
      r'^(read|screen padho|screen padh|screen dekho|read screen|show screen|स्क्रीन पढ़ो)$',
    ).hasMatch(value)) {
      return PhoneWorkflow.fromJson([
        {'action': 'read'},
      ]);
    }
    if (RegExp(
      r'^(back|go back|peeche jao|piche jao|वापस जाओ)$',
    ).hasMatch(value)) {
      return PhoneWorkflow.fromJson([
        {'action': 'back'},
      ]);
    }
    if (RegExp(
      r'^(scroll down|neeche scroll|niche scroll|नीचे स्क्रोल)$',
    ).hasMatch(value)) {
      return PhoneWorkflow.fromJson([
        {'action': 'scroll_forward'},
      ]);
    }
    if (RegExp(r'^(scroll up|upar scroll|ऊपर स्क्रोल)$').hasMatch(value)) {
      return PhoneWorkflow.fromJson([
        {'action': 'scroll_backward'},
      ]);
    }
    return null;
  }
}
