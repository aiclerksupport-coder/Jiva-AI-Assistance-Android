import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/conversation_context.dart';

void main() {
  test('turn memory is bounded and ordered', () {
    final context = ConversationContext(maxTurns: 2, maxCharacters: 400);
    context.appendCompletedTurn('one', 'answer one');
    context.appendCompletedTurn('two', 'answer two');
    context.appendCompletedTurn('three', 'answer three');
    final content = context.contentsWithUserTurn('four');
    expect(context.turnCount, 2);
    expect(content.length, 5);
    expect(((content.first['parts'] as List).first as Map)['text'], 'two');
    context.clear();
    expect(context.turnCount, 0);
  });
  test('rejects excessive untrusted context', () {
    final context = ConversationContext();
    expect(() => context.contentsWithUserTurn('x' * 2001), throwsArgumentError);
  });
}
