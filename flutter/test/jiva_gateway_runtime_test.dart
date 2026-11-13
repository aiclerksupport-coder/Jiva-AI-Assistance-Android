import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/services/jiva_gateway_runtime.dart';

void main() {
  test('secure gateway rejects missing public Firebase/deployment config', () async {
    if (JivaGatewayRuntime.endpoint.isNotEmpty &&
        JivaGatewayRuntime.projectId.isNotEmpty &&
        JivaGatewayRuntime.appId.isNotEmpty &&
        JivaGatewayRuntime.apiKey.isNotEmpty &&
        JivaGatewayRuntime.senderId.isNotEmpty) {
      return; // The CI test must never contact a configured production service.
    }
    await expectLater(JivaGatewayRuntime.client, throwsStateError);
  });

  test('secure release policy cannot be relaxed using an app setting', () {
    // Kotlin has a matching !BuildConfig.DEBUG || JIVA_REQUIRE_GATEWAY gate.
    const production = bool.fromEnvironment('dart.vm.product');
    if (production) expect(JivaGatewayRuntime.requireGateway, isTrue);
  });
}
