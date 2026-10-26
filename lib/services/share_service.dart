import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:network_info_plus/network_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart' as shelf_io;
import 'package:shelf_router/shelf_router.dart';
import 'package:mime/mime.dart';
import 'package:uuid/uuid.dart';

class SharedItem {
  final String id;
  final String name;
  final String path;
  final int size;
  final bool isApk;
  final DateTime addedAt;

  SharedItem({
    required this.id,
    required this.name,
    required this.path,
    required this.size,
    this.isApk = false,
    DateTime? addedAt,
  }) : addedAt = addedAt ?? DateTime.now();

  String get sizeLabel {
    if (size < 1024) return '$size B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)} KB';
    if (size < 1024 * 1024 * 1024) return '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
    return '${(size / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }
}

class ShareService extends ChangeNotifier {
  final List<SharedItem> _items = [];
  HttpServer? _server;
  String? _localIp;
  int _port = 8765;
  bool _isSharing = false;
  String? _password;
  String _sessionToken = const Uuid().v4();

  List<SharedItem> get items => List.unmodifiable(_items);
  bool get isSharing => _isSharing;
  String? get localIp => _localIp;
  int get port => _port;
  String? get password => _password;
  String get shareUrl => _localIp != null ? 'http://$_localIp:$_port' : '';

  Future<void> init() async {
    await _detectIp();
  }

  Future<void> _detectIp() async {
    try {
      final info = NetworkInfo();
      _localIp = await info.getWifiIP();
      if (_localIp == null || _localIp == '0.0.0.0') {
        for (var interface in await NetworkInterface.list()) {
          for (var addr in interface.addresses) {
            if (addr.type == InternetAddressType.IPv4 && !addr.isLoopback) {
              _localIp = addr.address;
              break;
            }
          }
          if (_localIp != null) break;
        }
      }
    } catch (e) {
      debugPrint('IP detect error: $e');
      _localIp = '127.0.0.1';
    }
    notifyListeners();
  }

  void setPassword(String? pw) {
    _password = (pw == null || pw.isEmpty) ? null : pw;
    notifyListeners();
  }

  void setPort(int port) {
    if (port > 1024 && port < 65535) {
      _port = port;
      notifyListeners();
    }
  }

  Future<void> addFile(String filePath, {bool isApk = false}) async {
    final file = File(filePath);
    if (!await file.exists()) return;

    final name = p.basename(filePath);
    final size = await file.length();
    final id = const Uuid().v4();

    _items.add(SharedItem(
      id: id,
      name: name,
      path: filePath,
      size: size,
      isApk: isApk || name.toLowerCase().endsWith('.apk'),
    ));
    notifyListeners();
  }

  Future<void> addFiles(List<String> paths) async {
    for (final path in paths) {
      await addFile(path);
    }
  }

  void removeItem(String id) {
    _items.removeWhere((e) => e.id == id);
    notifyListeners();
  }

  void clearAll() {
    _items.clear();
    notifyListeners();
  }

  Future<bool> startSharing() async {
    if (_isSharing) return true;
    if (_items.isEmpty) return false;

    await _detectIp();

    final router = Router();

    router.get('/', (Request request) {
      if (_password != null) {
        final token = request.url.queryParameters['token'];
        if (token != _sessionToken && token != _password) {
          return Response.ok(_loginHtml(), headers: {'content-type': 'text/html; charset=utf-8'});
        }
      }
      return Response.ok(_buildIndexHtml(), headers: {'content-type': 'text/html; charset=utf-8'});
    });

    router.get('/download/<id>', (Request request, String id) async {
      final item = _items.where((e) => e.id == id).firstOrNull;
      if (item == null) return Response.notFound('File not found');

      final file = File(item.path);
      if (!await file.exists()) return Response.notFound('File missing');

      final mimeType = lookupMimeType(item.name) ?? 'application/octet-stream';
      final bytes = await file.readAsBytes();

      return Response.ok(
        bytes,
        headers: {
          'content-type': mimeType,
          'content-disposition': 'attachment; filename="${item.name}"',
          'content-length': '${bytes.length}',
        },
      );
    });

    router.post('/login', (Request request) async {
      final body = await request.readAsString();
      if (body.contains(_password ?? '___')) {
        return Response.found('/?token=$_sessionToken');
      }
      return Response.ok(_loginHtml(error: true), headers: {'content-type': 'text/html; charset=utf-8'});
    });

    final handler = const Pipeline()
        .addMiddleware(logRequests())
        .addHandler(router.call);

    try {
      _server = await shelf_io.serve(handler, InternetAddress.anyIPv4, _port);
      _isSharing = true;
      notifyListeners();
      debugPrint('Server running on http://$_localIp:$_port');
      return true;
    } catch (e) {
      debugPrint('Server start failed: $e');
      return false;
    }
  }

  Future<void> stopSharing() async {
    await _server?.close(force: true);
    _server = null;
    _isSharing = false;
    notifyListeners();
  }

  String _loginHtml({bool error = false}) {
    return '''
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>LanDrop – Password</title>
  <style>
    body { font-family: system-ui; background: #0f0f13; color: #eee; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
    .card { background: #1a1a22; padding: 2rem; border-radius: 16px; width: 90%; max-width: 360px; text-align: center; }
    input { width: 100%; padding: 12px; border-radius: 10px; border: 1px solid #333; background: #121218; color: #fff; margin: 12px 0; font-size: 16px; }
    button { width: 100%; padding: 12px; background: #6c5ce7; color: white; border: none; border-radius: 10px; font-size: 16px; font-weight: 600; }
    .error { color: #ff6b6b; margin-top: 8px; }
  </style>
</head>
<body>
  <div class="card">
    <h2>🔒 LanDrop</h2>
    <p>Enter password to access files</p>
    <form method="POST" action="/login">
      <input type="password" name="password" placeholder="Password" required>
      <button type="submit">Unlock</button>
    </form>
    ${error ? '<p class="error">Wrong password</p>' : ''}
  </div>
</body>
</html>
''';
  }

  String _buildIndexHtml() {
    final rows = _items.map((item) {
      final icon = item.isApk ? '📱' : '📄';
      return '''
      <div class="file">
        <div class="icon">$icon</div>
        <div class="info">
          <div class="name">${_escape(item.name)}</div>
          <div class="meta">${item.sizeLabel}</div>
        </div>
        <a class="btn" href="/download/${item.id}">Download</a>
      </div>
      ''';
    }).join('\n');

    return '''
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
  <title>LanDrop – Shared Files</title>
  <style>
    :root { --bg: #0f0f13; --card: #1a1a22; --accent: #6c5ce7; --text: #e8e8ed; --muted: #9a9aab; }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, system-ui, sans-serif; background: var(--bg); color: var(--text); min-height: 100vh; }
    .header { background: linear-gradient(135deg, #6c5ce7, #a29bfe); padding: 1.2rem; text-align: center; }
    .header h1 { font-size: 1.4rem; }
    .header p { opacity: 0.9; font-size: 0.85rem; margin-top: 4px; }
    .container { max-width: 640px; margin: 0 auto; padding: 1rem; }
    .file { display: flex; align-items: center; gap: 12px; background: var(--card); border-radius: 14px; padding: 14px; margin-bottom: 10px; }
    .icon { font-size: 1.6rem; width: 44px; text-align: center; }
    .info { flex: 1; min-width: 0; }
    .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .meta { font-size: 0.8rem; color: var(--muted); margin-top: 2px; }
    .btn { background: var(--accent); color: white; text-decoration: none; padding: 8px 14px; border-radius: 10px; font-weight: 600; font-size: 0.9rem; white-space: nowrap; }
    .empty { text-align: center; padding: 3rem 1rem; color: var(--muted); }
    footer { text-align: center; padding: 1.5rem; color: var(--muted); font-size: 0.8rem; }
  </style>
</head>
<body>
  <div class="header">
    <h1>LanDrop</h1>
    <p>${_items.length} file${_items.length == 1 ? '' : 's'} shared · No app needed</p>
  </div>
  <div class="container">
    ${_items.isEmpty ? '<div class="empty">No files shared yet</div>' : rows}
  </div>
  <footer>Files stay on the sender’s device · Same Wi-Fi only</footer>
</body>
</html>
''';
  }

  String _escape(String s) => s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');

  @override
  void dispose() {
    stopSharing();
    super.dispose();
  }
}
