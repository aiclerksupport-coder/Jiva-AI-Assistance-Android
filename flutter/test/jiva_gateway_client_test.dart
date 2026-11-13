import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jiva_assistance/services/jiva_gateway_client.dart';

void main() {
  test('gateway sends Firebase ID + App Check, never Gemini API key', () async {
    final client = MockClient((request) async {
      expect(request.url.toString(), 'https://jiva.example/v1/interpret');
      expect(request.headers['Authorization'], 'Bearer firebase-user-token');
      expect(request.headers['X-Firebase-AppCheck'], 'firebase-appcheck-token');
      expect(request.headers.containsKey('x-goog-api-key'), false);
      expect(jsonDecode(request.body)['text'], 'Open Maps');
      return http.Response(jsonEncode({'action':'OPEN_APP','reply':'Review action'}),200);
    });
    final gateway = JivaGatewayClient(
      baseUrl: Uri.parse('https://jiva.example/'),
      client: client,
      tokens: () async => const JivaGatewayTokens(
        firebaseIdToken:'firebase-user-token',
        appCheckToken:'firebase-appcheck-token',
      ),
    );
    final result = await gateway.interpret('Open Maps');
    expect(result['action'], 'OPEN_APP');
    gateway.close();
  });

  test('gateway fails closed without user token', () async {
    final gateway = JivaGatewayClient(
      baseUrl: Uri.parse('https://jiva.example/'),
      tokens: () async => const JivaGatewayTokens(firebaseIdToken:'',appCheckToken:''),
    );
    await expectLater(gateway.interpret('Hello'), throwsStateError);
    gateway.close();
  });

  test('live-token requires identity, attestation and never follows redirects', () async {
    var calls = 0;
    final gateway = JivaGatewayClient(
      baseUrl: Uri.parse('https://jiva.example/'),
      client: MockClient((request) async {
        calls++;
        expect(request.url.toString(), 'https://jiva.example/v1/live-token');
        expect(request.followRedirects, isFalse);
        expect(request.headers['Authorization'], 'Bearer verified-user');
        expect(request.headers['X-Firebase-AppCheck'], 'valid-attestation');
        return http.Response(jsonEncode({'token':'ephemeral-one-session'}), 200);
      }),
      tokens: () async => const JivaGatewayTokens(
        firebaseIdToken: 'verified-user',
        appCheckToken: 'valid-attestation',
      ),
    );
    expect(await gateway.createLiveToken(), 'ephemeral-one-session');
    expect(calls, 1);
    gateway.close();
  });

  test('redirects are rejected without forwarding security credentials', () async {
    final gateway = JivaGatewayClient(
      baseUrl: Uri.parse('https://jiva.example/'),
      client: MockClient((request) async {
        expect(request.followRedirects, isFalse);
        return http.Response('', 302, headers: {
          'location': 'https://attacker.example/harvest',
        });
      }),
      tokens: () async => const JivaGatewayTokens(
        firebaseIdToken: 'signed-in',
        appCheckToken: 'attested',
      ),
    );
    await expectLater(gateway.interpret('hello'), throwsStateError);
    gateway.close();
  });

  test('gateway refuses non-HTTPS endpoints', () {
    expect(
      () => JivaGatewayClient(
        baseUrl: Uri.parse('http://jiva.example/'),
        tokens: () async => const JivaGatewayTokens(firebaseIdToken:'x',appCheckToken:'y'),
      ),
      throwsArgumentError,
    );
  });
}
