/// Pure policy for accepting a new wake-word callback.
///
/// Detection is valid only while listening is armed and no active command,
/// pending confirmation or previous wake handoff owns the microphone.
class WakeEventPolicy {
  const WakeEventPolicy._();

  static bool shouldQueue({
    required bool enabled,
    required bool active,
    required bool pending,
    required bool busy,
    required bool listening,
    required bool hasActionPlan,
  }) =>
      enabled &&
      active &&
      !pending &&
      !busy &&
      !listening &&
      !hasActionPlan;
}
