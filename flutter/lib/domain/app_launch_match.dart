/// Resolves an installed launcher label only if the match is unambiguous.
/// Prefix matches are permitted when unique; arbitrary substring matches are not.
class AppLaunchMatch {
  const AppLaunchMatch._();

  static int? select(String query, List<String> labels) {
    final requested = _normalize(query);
    if (requested.isEmpty) return null;
    final normalized = labels.map(_normalize).toList(growable: false);
    final exact = <int>[
      for (var index = 0; index < normalized.length; index++)
        if (normalized[index] == requested) index,
    ];
    if (exact.isNotEmpty) return exact.length == 1 ? exact.single : null;
    if (requested.length < 4) return null;
    final prefix = <int>[
      for (var index = 0; index < normalized.length; index++)
        if (normalized[index].isNotEmpty &&
            normalized[index].startsWith(requested))
          index,
    ];
    return prefix.length == 1 ? prefix.single : null;
  }

  static String _normalize(String text) =>
      text.trim().replaceAll(RegExp(r'\s+'), ' ').toLowerCase();
}
