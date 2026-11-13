import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/app_launch_match.dart';

void main() {
  const labels = [
    'Phone', 'PhonePe', 'Google Maps', 'Maps Go', 'My Local Bank App',
  ];

  test('exact launcher labels win over prefixes', () {
    expect(AppLaunchMatch.select('Phone', labels), 0);
    expect(AppLaunchMatch.select('phonepe', labels), 1);
  });
  test('unique prefix resolves but ambiguous name never guesses', () {
    expect(AppLaunchMatch.select('Google Map', labels), 2);
    expect(AppLaunchMatch.select('Google', ['Google Meet', 'Google Maps']), isNull);
    expect(AppLaunchMatch.select('Maps', ['Maps', 'Maps']), isNull);
  });
  test('empty, short or substring app name never launches arbitrary app', () {
    for (final query in ['', '   ', 'ph', 'Local Bank', 'SMS']) {
      expect(AppLaunchMatch.select(query, labels), isNull, reason: query);
    }
  });
}
