import 'dart:io';
import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:llamadart/llamadart.dart';
import 'package:path_provider/path_provider.dart';

/// Explicit opt-in local GGUF model. No automatic network access or fallback.
/// Caller must keep this service in the visible app, not in a WorkManager job.
class OnDeviceLlm {
  LlamaEngine? _engine;
  bool _busy = false;
  bool get isLoaded => _engine != null;
  static const maxModelBytes = 2200 * 1024 * 1024;
  static const maxPromptChars = 1500;

  /// Import a user-selected GGUF inside private application storage. A checksum
  /// is computed before and after copying to detect accidental corruption.
  /// This is NOT provenance verification: user must trust the model publisher.
  Future<String> importModel(String sourcePath) async {
    if (_engine != null || _busy) {
      throw StateError('Close the model first');
    }
    final source = File(sourcePath);
    if (!await source.exists() || !sourcePath.toLowerCase().endsWith('.gguf')) {
      throw StateError('Select a local .gguf file');
    }
    final size = await source.length();
    if (size < 1024 * 1024 || size > maxModelBytes) {
      throw StateError('Model must be between 1 MB and 2.2 GB');
    }
    final base = await getApplicationSupportDirectory();
    final dir = Directory('${base.path}/jiva_offline_model');
    await dir.create(recursive: true);
    final target = File('${dir.path}/active.gguf');
    final staged = File('${dir.path}/staged.gguf');
    if (await staged.exists()) {
      await staged.delete();
    }
    try {
      final originalDigest = (await sha256.bind(source.openRead()).first)
          .toString();
      await source.copy(staged.path);
      final copyDigest = (await sha256.bind(staged.openRead()).first)
          .toString();
      if (originalDigest != copyDigest) {
        throw StateError('Model checksum mismatch');
      }
      if (await target.exists()) {
        await target.delete();
      }
      await staged.rename(target.path);
      await File(
        '${dir.path}/sha256.txt',
      ).writeAsString(originalDigest, flush: true);
      return originalDigest;
    } finally {
      if (await staged.exists()) {
        await staged.delete();
      }
    }
  }

  Future<String> _verifiedPath() async {
    final base = await getApplicationSupportDirectory();
    final dir = Directory('${base.path}/jiva_offline_model');
    final model = File('${dir.path}/active.gguf');
    final digestFile = File('${dir.path}/sha256.txt');
    if (!await model.exists() || !await digestFile.exists()) {
      throw StateError('Import your approved GGUF model first');
    }
    final expected = (await digestFile.readAsString()).trim();
    if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(expected) ||
        (await sha256.bind(model.openRead()).first).toString() != expected) {
      throw StateError('Local model integrity check failed');
    }
    return model.path;
  }

  Future<void> load() async {
    if (_busy) {
      throw StateError('Model is busy');
    }
    if (_engine != null) {
      return;
    }
    _busy = true;
    try {
      final path = await _verifiedPath();
      final engine = LlamaEngine(LlamaBackend());
      try {
        await engine.setModel(LlamaModel(ModelSource.path(path)));
        _engine = engine;
      } catch (_) {
        await engine.dispose();
        rethrow;
      }
    } finally {
      _busy = false;
    }
  }

  Future<String> answer(String prompt) async {
    final engine = _engine;
    if (engine == null) {
      throw StateError('Offline model not loaded');
    }
    if (_busy || prompt.trim().isEmpty || prompt.length > maxPromptChars) {
      throw StateError('Invalid prompt or model busy');
    }
    _busy = true;
    try {
      return await engine.create([
        LlamaChatMessage.fromText(
          role: LlamaChatRole.system,
          text:
              'You are Jiva, a helpful assistant. Answer in the user language. '
              'Never claim any phone action was performed. You have no device tools.',
        ),
        LlamaChatMessage.fromText(role: LlamaChatRole.user, text: prompt),
      ], params: const GenerationParams(maxTokens: 220, temp: 0.5)).text();
    } finally {
      _busy = false;
    }
  }

  Future<String> proposeWorkflow(String task) async {
    final reply = await answer(
      'Return ONLY compact JSON with key steps, max 5. '
      'Actions allowed: read, click, scroll_forward, scroll_backward, back. '
      'For click include exact visible label. No banking, payments, messages, '
      'credentials, settings changes, or assumptions that any screen was seen. '
      'If unsure return {"steps":[{"action":"read"}]}. Task: $task',
    );
    final match = RegExp(r'\{[\s\S]*\}').firstMatch(reply);
    if (match == null) {
      throw const FormatException('Model did not return a plan');
    }
    final raw = jsonDecode(match.group(0)!);
    if (raw is! Map || raw['steps'] is! List) {
      throw const FormatException('Invalid local workflow');
    }
    return jsonEncode(raw['steps']);
  }

  Future<void> dispose() async {
    if (_busy) {
      throw StateError('Model is busy');
    }
    final engine = _engine;
    _engine = null;
    if (engine != null) {
      await engine.dispose();
    }
  }
}
