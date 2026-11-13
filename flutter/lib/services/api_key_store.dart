import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class ApiKeyStore {
  ApiKeyStore(this._storage);

  static const _keyName = 'jiva_gemini_api_key';
  static const _picovoiceKeyName = 'jiva_picovoice_access_key';
  static const _jivaModelName = 'jiva_wake_model_jiva';
  static const _givaModelName = 'jiva_wake_model_giva';
  static const _assistantEnabledName = 'jiva_assistant_enabled';
  static const _noiseProcessingName = 'jiva_noise_processing_enabled';
  static const _patientListeningName = 'jiva_patient_listening_enabled';
  final FlutterSecureStorage _storage;

  Future<String> read() async => await _storage.read(key: _keyName) ?? '';

  Future<void> save(String value) async {
    if (value.trim().isEmpty) {
      throw ArgumentError('API key cannot be empty.');
    }
    await _storage.write(key: _keyName, value: value.trim());
  }

  Future<String> readPicovoiceAccessKey() async =>
      await _storage.read(key: _picovoiceKeyName) ?? '';

  Future<void> savePicovoiceAccessKey(String value) async {
    if (value.trim().isEmpty) {
      throw ArgumentError('Picovoice AccessKey cannot be empty.');
    }
    await _storage.write(key: _picovoiceKeyName, value: value.trim());
  }

  Future<void> saveWakeWordModelPath(String name, String path) async {
    final key = switch (name) {
      'jiva' => _jivaModelName,
      'giva' => _givaModelName,
      _ => throw ArgumentError('Unsupported wake-word model slot.'),
    };
    if (!path.toLowerCase().endsWith('.ppn')) {
      throw ArgumentError('Wake-word model must be a .ppn file.');
    }
    await _storage.write(key: key, value: path);
  }

  Future<Map<String, String>> readWakeWordModelPaths() async {
    final jiva = await _storage.read(key: _jivaModelName);
    final giva = await _storage.read(key: _givaModelName);
    return {
      if (jiva != null && jiva.isNotEmpty) 'jiva': jiva,
      if (giva != null && giva.isNotEmpty) 'giva': giva,
    };
  }

  Future<void> clearWakeWordConfig() async {
    await _storage.delete(key: _picovoiceKeyName);
    await _storage.delete(key: _jivaModelName);
    await _storage.delete(key: _givaModelName);
  }

  Future<void> clear() => _storage.delete(key: _keyName);

  Future<bool> readAssistantEnabled() async =>
      (await _storage.read(key: _assistantEnabledName)) != 'false';

  Future<void> saveAssistantEnabled(bool enabled) async {
    await _storage.write(
      key: _assistantEnabledName,
      value: enabled ? 'true' : 'false',
    );
  }

  Future<bool> readNoiseProcessingEnabled() async =>
      (await _storage.read(key: _noiseProcessingName)) != 'false';

  Future<void> saveNoiseProcessingEnabled(bool enabled) async {
    await _storage.write(
      key: _noiseProcessingName,
      value: enabled ? 'true' : 'false',
    );
  }

  Future<bool> readPatientListeningEnabled() async =>
      (await _storage.read(key: _patientListeningName)) != 'false';

  Future<void> savePatientListeningEnabled(bool enabled) async {
    await _storage.write(
      key: _patientListeningName,
      value: enabled ? 'true' : 'false',
    );
  }
}
