import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:provider/provider.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../services/share_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _passwordController = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<ShareService>().init();
      _requestPermissions();
    });
  }

  Future<void> _requestPermissions() async {
    await [
      Permission.storage,
      Permission.manageExternalStorage,
      Permission.photos,
      Permission.videos,
    ].request();
  }

  Future<void> _pickFiles() async {
    final result = await FilePicker.platform.pickFiles(
      allowMultiple: true,
      type: FileType.any,
      withData: false,
    );
    if (result != null) {
      final paths = result.paths.whereType<String>().toList();
      await context.read<ShareService>().addFiles(paths);
    }
  }

  Future<void> _toggleShare() async {
    final service = context.read<ShareService>();
    if (service.isSharing) {
      await service.stopSharing();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Sharing stopped')),
        );
      }
    } else {
      if (service.items.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Add some files first')),
        );
        return;
      }
      service.setPassword(_passwordController.text.trim().isEmpty
          ? null
          : _passwordController.text.trim());
      final ok = await service.startSharing();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(ok
                ? 'Sharing started! Scan QR or open the link'
                : 'Failed to start server. Try another port.'),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final service = context.watch<ShareService>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('LanDrop'),
        centerTitle: true,
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            color: const Color(0xFF1A1A22),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Icon(
                    service.isSharing ? Icons.wifi_tethering : Icons.wifi_tethering_off,
                    size: 48,
                    color: service.isSharing ? Colors.greenAccent : Colors.grey,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    service.isSharing ? 'Sharing Active' : 'Not Sharing',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  if (service.isSharing && service.shareUrl.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    SelectableText(
                      service.shareUrl,
                      style: const TextStyle(
                        color: Color(0xFFA29BFE),
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 16),
                    QrImageView(
                      data: service.shareUrl,
                      version: QrVersions.auto,
                      size: 200,
                      backgroundColor: Colors.white,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Scan with any phone camera / browser',
                      style: TextStyle(color: Colors.grey[400], fontSize: 13),
                    ),
                  ],
                ],
              ),
            ),
          ),

          const SizedBox(height: 20),

          TextField(
            controller: _passwordController,
            decoration: InputDecoration(
              labelText: 'Optional password',
              hintText: 'Leave empty for open access',
              prefixIcon: const Icon(Icons.lock_outline),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              filled: true,
              fillColor: const Color(0xFF1A1A22),
            ),
            obscureText: true,
            enabled: !service.isSharing,
          ),

          const SizedBox(height: 20),

          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: service.isSharing ? null : _pickFiles,
                  icon: const Icon(Icons.add),
                  label: const Text('Add Files'),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  onPressed: _toggleShare,
                  icon: Icon(service.isSharing ? Icons.stop : Icons.play_arrow),
                  label: Text(service.isSharing ? 'Stop' : 'Start Share'),
                  style: FilledButton.styleFrom(
                    backgroundColor: service.isSharing ? Colors.redAccent : const Color(0xFF6C5CE7),
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                ),
              ),
            ],
          ),

          const SizedBox(height: 24),

          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Files (${service.items.length})',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              if (service.items.isNotEmpty && !service.isSharing)
                TextButton(
                  onPressed: () => service.clearAll(),
                  child: const Text('Clear all'),
                ),
            ],
          ),
          const SizedBox(height: 8),

          if (service.items.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 40),
              child: Center(
                child: Text(
                  'No files added yet.\nTap “Add Files” or share from other apps.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
              ),
            )
          else
            ...service.items.map((item) => Card(
                  color: const Color(0xFF1A1A22),
                  margin: const EdgeInsets.only(bottom: 8),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  child: ListTile(
                    leading: CircleAvatar(
                      backgroundColor: item.isApk
                          ? Colors.green.withOpacity(0.2)
                          : const Color(0xFF6C5CE7).withOpacity(0.2),
                      child: Icon(
                        item.isApk ? Icons.android : Icons.insert_drive_file,
                        color: item.isApk ? Colors.greenAccent : const Color(0xFFA29BFE),
                      ),
                    ),
                    title: Text(item.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                    subtitle: Text(item.sizeLabel),
                    trailing: service.isSharing
                        ? null
                        : IconButton(
                            icon: const Icon(Icons.close, size: 20),
                            onPressed: () => service.removeItem(item.id),
                          ),
                  ),
                )),

          const SizedBox(height: 40),
          const Text(
            'How it works\n1. Add files or APKs\n2. Tap Start Share\n3. Other person opens the link or scans QR in any browser\n4. They download — no app needed on their side',
            style: TextStyle(color: Colors.grey, height: 1.5),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _passwordController.dispose();
    super.dispose();
  }
}
