import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/ui/jiva_theme.dart';
import 'package:jiva_assistance/ui/jiva_widgets.dart';
import 'package:jiva_assistance/ui/jiva_about_screen.dart';

void main() {
  testWidgets('Quick action label stays single-line on narrow layout', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    await tester.pumpWidget(
      MaterialApp(
        theme: JivaTheme.light,
        home: const Scaffold(
          body: Center(
            child: SizedBox(
              width: 95,
              height: 60,
              child: JivaActionTile(
                label: 'Reminders',
                icon: Icons.calendar_month,
                onPressed: null,
              ),
            ),
          ),
        ),
      ),
    );
    expect(find.text('Reminders'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Branded about page displays version and unregistered brand', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(theme: JivaTheme.light, home: const JivaAboutScreen()),
    );
    expect(find.text('Version 1.0.1  ·  Build 2'), findsOneWidget);
    expect(find.text('Brand (proposed / unregistered)'), findsOneWidget);
  });

  testWidgets('Quick tile calls its assigned action exactly once per tap', (
    tester,
  ) async {
    var calls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: JivaActionTile(
            label: 'Apps',
            icon: Icons.apps,
            onPressed: () => calls++,
          ),
        ),
      ),
    );
    await tester.tap(find.text('Apps'));
    expect(calls, 1);
  });
}
