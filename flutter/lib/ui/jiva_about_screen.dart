import 'package:flutter/material.dart';
import 'jiva_theme.dart';

/// Static legal/version details. No claim is made that data never leaves the
/// device: Gemini and device speech providers can process enabled requests.
class JivaAboutScreen extends StatelessWidget {
  const JivaAboutScreen({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('About Jiva AI')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Center(
            child: Image.asset(
              'assets/branding/jiva_logo_master.png',
              height: 188,
              fit: BoxFit.contain,
              semanticLabel: 'Official Jiva AI orbital logo',
            ),
          ),
          const SizedBox(height: 8),
          Center(
            child: Text(
              'Jiva AI Assistance',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                color: JivaPalette.navy,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
          const SizedBox(height: 4),
          const Center(child: Text('Your Personal AI Assistant')),
          const SizedBox(height: 14),
          const Center(child: Chip(label: Text('Version 1.0.1  ·  Build 2'))),
          const SizedBox(height: 18),
          Card(
            child: Column(
              children: const [
                ListTile(
                  leading: Icon(Icons.person_outline, color: JivaPalette.teal),
                  title: Text('Developer'),
                  subtitle: Text('Kalpesh Chhabhaiya'),
                ),
                Divider(height: 1),
                ListTile(
                  leading: Icon(Icons.layers_outlined, color: JivaPalette.teal),
                  title: Text('Brand (proposed / unregistered)'),
                  subtitle: Text('Sanwariya'),
                ),
                Divider(height: 1),
                ListTile(
                  leading: Icon(Icons.code_outlined, color: JivaPalette.teal),
                  title: Text('Source project'),
                  subtitle: Text('Jiva-AI-Assistance-Android'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(
                        Icons.verified_user_outlined,
                        color: JivaPalette.teal,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        'Privacy & Data',
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(fontWeight: FontWeight.w800),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'Jiva uses the permissions you grant. Requests submitted to Gemini are sent '
                    'to Google with your API key. The device speech service may process audio '
                    'remotely. Offline AI can operate locally when an imported model is loaded. '
                    'Sensitive phone actions require review and approval.',
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 14),
          OutlinedButton.icon(
            icon: const Icon(Icons.info_outline),
            label: const Text('App Details'),
            onPressed: () => showAboutDialog(
              context: context,
              applicationName: 'Jiva AI Assistance',
              applicationVersion: '1.0.1 (build 2)',
              applicationLegalese:
                  'Kalpesh Chhabhaiya · Sanwariya (proposed brand)',
            ),
          ),
        ],
      ),
    ),
  );
}
