import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:reactive_ble_platform_interface/reactive_ble_platform_interface.dart';

class PollingEventChannel {
  final String name;
  final int id;

  const PollingEventChannel(this.name, this.id);

  Stream<List<int>> receiveBroadcastStream() {
    final loop = PollingEventLoop._sharedInstance!;
    return loop._createStream(name, id);
  }
}

class PollingEventLoop {
  static PollingEventLoop? _sharedInstance;

  Logger? _logger;
  final MethodChannel _methodChannel;

  final _keepEventLoopRunning = true;
  final _streams = <int, Stream<List<int>>>{};
  final _clients = <int, StreamController<List<int>>>{};

  factory PollingEventLoop(MethodChannel channel, {Logger? logger}) {
    // It is assumed that the method channel passed in will always be the same
    final instance = _sharedInstance;
    if (instance == null) {
      return _sharedInstance = PollingEventLoop._(channel, logger: logger);
    } else {
      return instance.._logger = logger;
    }
  }

  PollingEventLoop._(this._methodChannel, {Logger? logger}) : _logger = logger {
    Future<void>.microtask(_eventSiphoningLoop);
  }

  Stream<List<int>> _createStream(String name, int id) {
    final stream = _streams[id];
    if (stream != null) {
      return stream;
    }
    // ignore: close_sinks
    final controller = StreamController<List<int>>.broadcast(
      onCancel: () async {
        _logger?.log("onCancel called for channel $id");
        try {
          await _methodChannel.invokeMethod<void>("_onCancel", id);
        }
        // ignore: avoid_catches_without_on_clauses
        catch (exception, stack) {
          FlutterError.reportError(FlutterErrorDetails(
            exception: exception,
            stack: stack,
            library: 'flutter_reactive_ble library',
            context: ErrorDescription('while de-activating platform stream on channel $name'),
          ));
        }
      },
      onListen: () async {
        _logger?.log("onListen called for channel $id");
        try {
          await _methodChannel.invokeMethod<void>("_onListen", id);
        }
        // ignore: avoid_catches_without_on_clauses
        catch (exception, stack) {
          FlutterError.reportError(FlutterErrorDetails(
            exception: exception,
            stack: stack,
            library: 'flutter_reactive_ble library',
            context: ErrorDescription('while activating platform stream on channel $name'),
          ));
        }
      },
    );
    _clients[id] = controller;
    return _streams[id] = controller.stream;
  }

  Future<void> _eventSiphoningLoop() async {
    //_logger?.log('BLE event loop: starting');
    await _methodChannel.invokeMethod<void>("_startEventLoop");
    while (_keepEventLoopRunning) {
      //_logger?.log('BLE event loop: calling _readEvents');
      final eventBytes = await _methodChannel.invokeListMethod<int>("_readEvents");
      //_logger?.log('BLE event loop: _readEvents returned');
      if (eventBytes != null) {
        // Multiple events may be concatenated in the list returned
        for (var index = 0; index < eventBytes.length;) {
          assert(index + 4 <= eventBytes.length, "Don't have enough bytes for a 2-byte channel ID and 2-byte length");
          final channelIdAndError = ((eventBytes[index + 0] + 256) & 0xFF) * 256 + ((eventBytes[index + 1] + 256) & 0xFF);
          final length = ((eventBytes[index + 2] + 256) & 0xFF) * 256 + ((eventBytes[index + 3] + 256) & 0xFF);
          final channelId = channelIdAndError & 0xFF;
          final isError = (channelIdAndError & 0x8000) != 0;
          assert(index + 4 + length <= eventBytes.length, "Don't have enough bytes for the length indicated");
          //_logger?.log("BLE event loop: Dispatching event of length $length for channel $channelId error=$isError");
          // ignore: close_sinks
          final controller = _clients[channelId];
          if (!isError) {
            final event = eventBytes.sublist(index + 4, index + 4 + length);
            if (controller != null) {
              controller.add(event);
            } else {
              //_logger?.log("BLE event loop: No controller for channel $channelId");
            }
          } else {
            // Two zero-terminated UTF8 strings are the payload
            final start1 = index + 4;
            final end1 = _findFirstZero(eventBytes, start1, start1 + length);
            final start2 = end1 + 1;
            final end2 = _findFirstZero(eventBytes, start2, start1 + length);
            final code1 = String.fromCharCodes(eventBytes.sublist(start1, end1));
            final code2 = String.fromCharCodes(eventBytes.sublist(start2, end2));
            _logger?.log("BLE event loop: Error event: $code1, $code2");
            if (controller != null) {
              controller.addError(PlatformException(code: code1, message: code2));
            }
          }
          index += 4 + length;
        }
        //_logger?.log("BLE event loop: Dispatched all ${eventBytes.length} bytes of events");
      } else {
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
    }
    //_logger?.log('BLE event loop: end');
  }

  static int _findFirstZero(List<int> data, int start, int end) {
    for (var index = start; index < end; index++) {
      if (data[index] == 0) {
        return index;
      }
    }
    return end;
  }

}
