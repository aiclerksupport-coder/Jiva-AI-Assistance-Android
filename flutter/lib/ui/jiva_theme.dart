import 'package:flutter/material.dart';

/// One centrally owned design system for every Jiva screen and dialog.
abstract final class JivaPalette {
  static const navy = Color(0xFF06233E);
  static const darkNavy = Color(0xFF03182D);
  static const teal = Color(0xFF007D84);
  static const cyan = Color(0xFF00DBDD);
  static const blue = Color(0xFF0575D9);
  static const ice = Color(0xFFF0FBFD);
  static const ink = Color(0xFF0C233C);
}

abstract final class JivaTheme {
  static const accentGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [JivaPalette.navy, JivaPalette.teal, JivaPalette.cyan],
  );
  static final light = _build(Brightness.light);
  static final dark = _build(Brightness.dark);

  static ThemeData _build(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    final scheme =
        ColorScheme.fromSeed(
          seedColor: JivaPalette.teal,
          brightness: brightness,
        ).copyWith(
          primary: isDark ? JivaPalette.cyan : JivaPalette.teal,
          secondary: JivaPalette.blue,
          surface: isDark ? JivaPalette.navy : Colors.white,
          onSurface: isDark ? Colors.white : JivaPalette.ink,
        );
    final bg = isDark ? JivaPalette.darkNavy : JivaPalette.ice;
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: bg,
      appBarTheme: const AppBarTheme(
        backgroundColor: JivaPalette.navy,
        foregroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        color: isDark ? const Color(0xFF092C46) : Colors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 2,
        margin: EdgeInsets.zero,
        shadowColor: JivaPalette.teal.withValues(alpha: .14),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(24),
          side: BorderSide(
            color: isDark ? const Color(0xFF16495A) : const Color(0xFFCAECF2),
          ),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: isDark ? JivaPalette.navy : JivaPalette.ice,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: isDark ? JivaPalette.navy : JivaPalette.ice,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: isDark ? const Color(0xFF123A52) : Colors.white,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 16,
        ),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: Color(0xFF9DCFD8)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: const BorderSide(color: JivaPalette.teal, width: 2),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: JivaPalette.teal,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 17, vertical: 15),
          minimumSize: const Size(72, 48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: isDark ? JivaPalette.cyan : JivaPalette.navy,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          minimumSize: const Size(70, 48),
          side: const BorderSide(color: Color(0xFF8EBBC7)),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: isDark ? JivaPalette.cyan : JivaPalette.teal,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      switchTheme: SwitchThemeData(
        trackColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.selected) ? JivaPalette.teal : null,
        ),
      ),
      dividerTheme: const DividerThemeData(color: Color(0xFFB5DDE4)),
    );
  }
}

/// Deliberately preserves entire labels; scales text only inside allocated width.
class JivaButtonLabel extends StatelessWidget {
  final String label;
  const JivaButtonLabel(this.label, {super.key});
  @override
  Widget build(BuildContext context) => FittedBox(
    fit: BoxFit.scaleDown,
    child: Text(label, maxLines: 1, softWrap: false),
  );
}
