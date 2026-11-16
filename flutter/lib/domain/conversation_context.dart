/// Bounded, session-only conversational context. No API keys, screen captures,
/// personal contacts or workflow steps are persisted by this class.
class ConversationContext {
  ConversationContext({this.maxTurns = 6, this.maxCharacters = 4000})
    : assert(maxTurns > 0 && maxTurns <= 20),
      assert(maxCharacters >= 200 && maxCharacters <= 16000);

  final int maxTurns;
  final int maxCharacters;
  final List<(String, String)> _turns = [];

  int get turnCount => _turns.length;

  void clear() => _turns.clear();

  List<Map<String, Object>> contentsWithUserTurn(String userText) {
    final current = userText.trim();
    if (current.isEmpty || current.length > 2000) {
      throw ArgumentError('Conversation message length must be 1-2000.');
    }
    final entries = <Map<String, Object>>[];
    for (final (user, assistant) in _turns) {
      entries.add({
        'role': 'user',
        'parts': [
          {'text': user},
        ],
      });
      entries.add({
        'role': 'model',
        'parts': [
          {'text': assistant},
        ],
      });
    }
    entries.add({
      'role': 'user',
      'parts': [
        {'text': current},
      ],
    });
    return entries;
  }

  void appendCompletedTurn(String userText, String assistantText) {
    final user = userText.trim();
    final assistant = assistantText.trim();
    if (user.isEmpty || assistant.isEmpty) return;
    _turns.add((
      user.length > 1000 ? user.substring(0, 1000) : user,
      assistant.length > 1000 ? assistant.substring(0, 1000) : assistant,
    ));
    while (_turns.length > maxTurns || _characterCount > maxCharacters) {
      _turns.removeAt(0);
    }
  }

  int get _characterCount =>
      _turns.fold(0, (sum, pair) => sum + pair.$1.length + pair.$2.length);
}
