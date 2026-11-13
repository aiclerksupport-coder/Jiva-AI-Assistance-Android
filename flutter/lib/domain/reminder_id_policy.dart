/// Pure notification ID allocation: never overwrite an existing pending ID.
class ReminderIdPolicy {
  const ReminderIdPolicy._();

  static int next(Set<int> used, int Function() candidate) {
    for (var tries = 0; tries < 128; tries++) {
      final id = candidate();
      if (id > 0 && id < 0x7fffffff && !used.contains(id)) return id;
    }
    for (var id = 1; id <= 10000; id++) {
      if (!used.contains(id)) return id;
    }
    throw StateError('Could not allocate a unique reminder ID.');
  }
}
