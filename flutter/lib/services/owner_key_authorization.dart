import 'package:local_auth/local_auth.dart';

/// Every save/remove requires a new Android system authentication event.
/// PIN, pattern and password are handled ONLY by the operating system.
class OwnerKeyAuthorization {
  OwnerKeyAuthorization({Future<bool> Function(String action)? authenticate})
      : _authenticate = authenticate ?? _systemAuthenticate;

  final Future<bool> Function(String action) _authenticate;
  bool _inProgress = false;

  Future<bool> confirm(String action) async {
    if (action != 'save' && action != 'remove') {
      throw ArgumentError('Unknown key mutation.');
    }
    if (_inProgress) return false;
    _inProgress = true;
    try {
      return await _authenticate(action);
    } catch (_) {
      // Cancellation, missing device lock, lockout or plugin failures deny.
      return false;
    } finally {
      _inProgress = false;
    }
  }

  static Future<bool> _systemAuthenticate(String action) async {
    final auth = LocalAuthentication();
    if (!await auth.isDeviceSupported()) return false;
    return auth.authenticate(
      localizedReason: 'Confirm with your phone fingerprint or screen-lock '
          'PIN/password to $action your Gemini API key.',
      biometricOnly: false,
      sensitiveTransaction: true,
      persistAcrossBackgrounding: false,
    );
  }
}
