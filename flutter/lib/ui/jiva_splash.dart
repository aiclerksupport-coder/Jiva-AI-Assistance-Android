import 'package:flutter/material.dart';
import 'jiva_theme.dart';

/// Branded animated hand-off after Android's OS-managed launch splash.
/// No network requests or background permissions are started here.
class JivaSplashGate extends StatefulWidget {
  const JivaSplashGate({super.key, required this.destination});
  final Widget destination;
  @override
  State<JivaSplashGate> createState() => _JivaSplashGateState();
}

class _JivaSplashGateState extends State<JivaSplashGate>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  bool _showDestination = false;
  @override
  void initState() {
    super.initState();
    _controller =
        AnimationController(
            vsync: this,
            duration: const Duration(milliseconds: 1200),
          )
          ..forward().whenComplete(() {
            if (mounted) setState(() => _showDestination = true);
          });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_showDestination || MediaQuery.disableAnimationsOf(context)) {
      return widget.destination;
    }
    return Scaffold(
      backgroundColor: JivaPalette.darkNavy,
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          final t = Curves.easeOutCubic.transform(_controller.value);
          return Opacity(
            opacity: .55 + (.45 * t),
            child: Transform.scale(scale: 1.035 - .035 * t, child: child),
          );
        },
        child: SizedBox.expand(
          child: Image.asset(
            'assets/branding/jiva_splash_fullhd.jpg',
            fit: BoxFit.cover,
            semanticLabel: 'Jiva AI Assistance animated splash',
          ),
        ),
      ),
    );
  }
}
