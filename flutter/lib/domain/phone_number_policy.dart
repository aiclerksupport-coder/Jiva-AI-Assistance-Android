/// Normal dialer numbers only. Reject USSD/MMI and URI injection.
class PhoneNumberPolicy {
  const PhoneNumberPolicy._();

  static bool isSafe(String? value) {
    final number = value?.trim() ?? '';
    if (number.isEmpty || number.length > 32) return false;
    if (!RegExp(r'^\+?[0-9 ()\-.]+$').hasMatch(number)) return false;
    final digits = RegExp(r'[0-9]').allMatches(number).length;
    return digits >= 3 && digits <= 15;
  }
}
