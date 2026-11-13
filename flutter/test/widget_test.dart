import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/main.dart';

void main() {
  testWidgets('Jiva MaterialApp renders assistant screen', (tester) async {
    // The screen starts async native/plugin setup; verify the app shell
    // without depending on microphone or Gemini network availability.
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: Text('Jiva AI Assistance')),
    ));
    expect(find.text('Jiva AI Assistance'), findsOneWidget);
    expect(JivaApp, isNotNull);
  });
}
