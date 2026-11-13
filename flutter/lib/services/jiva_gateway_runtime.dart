import 'package:firebase_app_check/firebase_app_check.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';

import 'jiva_gateway_client.dart';

/// Secure release mode never reads or sends the legacy on-device Gemini key.
/// Public Firebase app configuration is injected at build time; NO service
/// account key or Gemini API key belongs in --dart-define.
class JivaGatewayRuntime {
  JivaGatewayRuntime._();

  // A release must NEVER be able to opt back into client-side BYOK.
  static const requireGateway =
      bool.fromEnvironment('dart.vm.product') ||
      bool.fromEnvironment('JIVA_REQUIRE_GATEWAY');
  static const endpoint = String.fromEnvironment('JIVA_GATEWAY_URL');
  static const projectId = String.fromEnvironment('JIVA_FIREBASE_PROJECT_ID');
  static const appId = String.fromEnvironment('JIVA_FIREBASE_APP_ID');
  static const apiKey = String.fromEnvironment('JIVA_FIREBASE_WEB_API_KEY');
  static const senderId = String.fromEnvironment('JIVA_FIREBASE_SENDER_ID');

  static Future<JivaGatewayClient>? _client;

  static Future<JivaGatewayClient> get client =>
      _client ??= _create().catchError((Object error) {
        _client = null; // Provisioning failures must be retryable.
        throw error;
      });

  static Future<JivaGatewayClient> _create() async {
    if (endpoint.isEmpty || projectId.isEmpty || appId.isEmpty ||
        apiKey.isEmpty || senderId.isEmpty) {
      throw StateError(
        'Secure Jiva gateway is not configured. Set the public Firebase '
        'app identifiers and HTTPS gateway URL in release build settings.',
      );
    }
    final url = Uri.parse(endpoint);
    if (url.scheme != 'https' || url.host.isEmpty ||
        url.userInfo.isNotEmpty || url.hasQuery || url.hasFragment) {
      throw StateError('Jiva gateway must have a trusted HTTPS URL.');
    }
    if (Firebase.apps.isEmpty) {
      await Firebase.initializeApp(options: const FirebaseOptions(
        apiKey: apiKey,
        appId: appId,
        messagingSenderId: senderId,
        projectId: projectId,
      ));
    }
    // Real Play Integrity is mandatory; never use the debug provider in a
    // production build, including sideloaded APKs.
    await FirebaseAppCheck.instance.activate(
      providerAndroid: const AndroidPlayIntegrityProvider(),
    );
    return JivaGatewayClient(
      baseUrl: url,
      tokens: () async {
        final user = FirebaseAuth.instance.currentUser;
        if (user == null || user.isAnonymous || !user.emailVerified) {
          throw StateError('Sign in to a verified Jiva account first.');
        }
        final idToken = await user.getIdToken();
        final attestation = await FirebaseAppCheck.instance.getToken(false);
        if (idToken == null || idToken.isEmpty ||
            attestation == null || attestation.isEmpty) {
          throw StateError('Could not obtain valid Firebase security tokens.');
        }
        return JivaGatewayTokens(
          firebaseIdToken: idToken,
          appCheckToken: attestation,
        );
      },
    );
  }

  static Future<void> signIn(String email, String password) async {
    if (email.trim().isEmpty || password.isEmpty) {
      throw ArgumentError('Email and password are required.');
    }
    await client;
    final result = await FirebaseAuth.instance.signInWithEmailAndPassword(
      email: email.trim(),
      password: password,
    );
    final user = result.user;
    if (user == null || user.isAnonymous) {
      throw StateError('A verified Jiva account is required.');
    }
    if (!user.emailVerified) {
      await user.sendEmailVerification();
      throw StateError('Verify your email, then sign in again.');
    }
  }
}
