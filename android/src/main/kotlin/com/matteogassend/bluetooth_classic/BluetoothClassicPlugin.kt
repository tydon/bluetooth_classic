package com.matteogassend.bluetooth_classic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** BluetoothClassicPlugin */
class BluetoothClassicPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
  PluginRegistry.RequestPermissionsResultListener, ActivityAware {

  private lateinit var channel: MethodChannel
  private lateinit var bluetoothDeviceChannel: EventChannel
  private lateinit var bluetoothReadChannel: EventChannel
  private lateinit var bluetoothStatusChannel: EventChannel

  private var bluetoothDeviceChannelSink: EventChannel.EventSink? = null
  private var bluetoothReadChannelSink: EventChannel.EventSink? = null
  private var bluetoothStatusChannelSink: EventChannel.EventSink? = null

  private lateinit var ba: BluetoothAdapter
  private lateinit var pluginActivity: Activity
  private lateinit var application: Context
  private lateinit var looper: Looper

  private var activeResult: MethodChannel.Result? = null
  private var permissionGranted: Boolean = false

  private var thread: ConnectedThread? = null
  private var socket: BluetoothSocket? = null
  private var device: BluetoothDevice? = null

  private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  // ================================
  // PAIRING SUPPORT
  // ================================
  private data class PendingConnect(
    val result: MethodChannel.Result,
    val deviceId: String,
    val serviceUuid: String
  )

  private var pendingConnectRequest: PendingConnect? = null

  private val bondReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) return

      val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
      val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

      when (state) {
        BluetoothDevice.BOND_BONDING -> {
          Log.i("BTClassic", "Bonding in progress…")
        }

        BluetoothDevice.BOND_BONDED -> {
          Log.i("BTClassic", "Bonding complete.")
          pendingConnectRequest?.let {
            val req = it
            pendingConnectRequest = null
            Log.i("BTClassic", "Retrying connection after bonding…")
            connect(req.result, req.deviceId, req.serviceUuid)
          }
        }

        BluetoothDevice.BOND_NONE -> {
          Log.e("BTClassic", "Bonding failed or cancelled.")
        }
      }
    }
  }

  // ================================
  // CONNECTED THREAD
  // ================================
  private inner class ConnectedThread(socket: BluetoothSocket) : Thread() {
    private val inputStream = socket.inputStream
    private val outputStream = socket.outputStream
    private val buffer: ByteArray = ByteArray(1024)
    var readStream = true

    override fun run() {
      var numBytes: Int
      val queue = ConcurrentLinkedQueue<ByteArray>()
      while (readStream) {
        try {
          val buffer: ByteArray = ByteArray(1024)
          numBytes = inputStream.read(buffer)
          queue.offer(ByteArray(numBytes) { buffer[it] })

          Handler(Looper.getMainLooper()).post {
            do {
              val b = queue.poll()
              if (b != null) publishBluetoothData(b)
            } while (b != null)
          }

        } catch (e: IOException) {
          android.util.Log.e("Bluetooth Read", "input stream disconnected", e)
          Handler(Looper.getMainLooper()).post { publishBluetoothStatus(0) }
          readStream = false
        }
      }
    }

    fun write(bytes: ByteArray) {
      try {
        outputStream.write(bytes)
      } catch (e: IOException) {
        readStream = false
        android.util.Log.e("Bluetooth Write", "could not send data", e)
        Handler(looper).post { publishBluetoothStatus(0) }
      }
    }
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    pluginActivity = binding.activity
  }

  override fun onDetachedFromActivity() {}

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

  override fun onDetachedFromActivityForConfigChanges() {}

  // ================================
  // EVENT CHANNEL SUPPORT
  // ================================
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent) {
      when (intent.action) {
        BluetoothDevice.ACTION_FOUND -> {
          val device: BluetoothDevice =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
          publishBluetoothDevice(device)
        }
      }
    }
  }

  private fun publishBluetoothData(data: ByteArray) {
    Handler(Looper.getMainLooper()).post {
      bluetoothReadChannelSink?.success(data)
    }
  }

  private fun publishBluetoothStatus(status: Int) {
    Handler(Looper.getMainLooper()).post {
      bluetoothStatusChannelSink?.success(status)
    }
  }

  private fun publishBluetoothDevice(device: BluetoothDevice) {
    Handler(Looper.getMainLooper()).post {
      bluetoothDeviceChannelSink?.success(
        hashMapOf("address" to device.address, "name" to device.name)
      )
    }
  }

  // ================================
  // ENGINE ATTACH
  // ================================
  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "com.matteogassend/bluetooth_classic")
    bluetoothDeviceChannel = EventChannel(binding.binaryMessenger, "com.matteogassend/bluetooth_classic/devices")
    bluetoothReadChannel = EventChannel(binding.binaryMessenger, "com.matteogassend/bluetooth_classic/read")
    bluetoothStatusChannel = EventChannel(binding.binaryMessenger, "com.matteogassend/bluetooth_classic/status")

    ba = BluetoothAdapter.getDefaultAdapter()
    looper = binding.applicationContext.mainLooper
    application = binding.applicationContext

    channel.setMethodCallHandler(this)

    // Register pairing/bonding receiver
    application.registerReceiver(
      bondReceiver,
      IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    )

    bluetoothDeviceChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        bluetoothDeviceChannelSink = events
      }

      override fun onCancel(arguments: Any?) {
        bluetoothDeviceChannelSink = null
      }
    })

    bluetoothReadChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        bluetoothReadChannelSink = events
      }

      override fun onCancel(arguments: Any?) {
        bluetoothReadChannelSink = null
      }
    })

    bluetoothStatusChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        bluetoothStatusChannelSink = events
      }

      override fun onCancel(arguments: Any?) {
        bluetoothStatusChannelSink = null
      }
    })
  }

  // ================================
  // METHOD CALL HANDLER
  // ================================
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "initPermissions" -> initPermissions(result)
      "getDevices" -> getDevices(result)
      "startDiscovery" -> startScan(result)
      "stopDiscovery" -> stopScan(result)
      "connect" -> connect(
        result,
        call.argument<String>("deviceId")!!,
        call.argument<String>("serviceUUID")!!
      )
      "disconnect" -> disconnect(result)
      "write" -> write(result, call.argument<String>("message")!!)
      "writeBytes" -> {
        val bytes = call.arguments as? ByteArray
        if (bytes != null) writeBytes(result, bytes)
        else result.error("invalid_args", "Expected ByteArray", null)
      }
      else -> result.notImplemented()
    }
  }

  // ================================
  // CONNECT — NOW WITH PAIRING!!!
  // ================================
  private fun connect(result: MethodChannel.Result, deviceId: String, serviceUuid: String) {
    backgroundExecutor.execute {

      val device = ba.getRemoteDevice(deviceId)
      publishBluetoothStatus(1)

      ba.cancelDiscovery()

      // If not bonded, request bonding first
      if (device.bondState != BluetoothDevice.BOND_BONDED) {

        pendingConnectRequest = PendingConnect(result, deviceId, serviceUuid)

        val started = device.createBond()
        if (!started) {
          Handler(Looper.getMainLooper()).post {
            result.error("bond_failed", "Could not initiate bonding.", null)
          }
        }
        return@execute
      }

      // Already bonded — attempt connection
      var socket: BluetoothSocket? = null
      val uuid = UUID.fromString(serviceUuid)

      try {
        // Secure
        try {
          socket = device.createRfcommSocketToServiceRecord(uuid)
          socket!!.connect()
        } catch (e: IOException) {
          Log.e("BTClassic", "Secure failed: ${e.message}")
        }

        // Insecure
        if (socket == null || !socket!!.isConnected) {
          try {
            socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
            socket!!.connect()
          } catch (e: IOException) {
            Log.e("BTClassic", "Insecure failed: ${e.message}")
          }
        }

        // Reflection fallback
        if (socket == null || !socket!!.isConnected) {
          try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            socket = method.invoke(device, 1) as BluetoothSocket
            socket!!.connect()
          } catch (e: Exception) {
            Log.e("BTClassic", "Reflection failed: ${e.message}")
          }
        }

        if (socket == null || !socket!!.isConnected)
          throw IOException("All connection attempts failed")

        thread = ConnectedThread(socket!!)
        thread!!.start()

        Handler(Looper.getMainLooper()).post {
          result.success(true)
          publishBluetoothStatus(2)
        }

      } catch (e: Exception) {
        Handler(Looper.getMainLooper()).post {
          publishBluetoothStatus(0)
          result.error("connection_failed", "could not connect to device $deviceId", e.message)
        }
        try { socket?.close() } catch (_: Exception) {}
      }
    }
  }

  // ================================
  // DISCONNECT
  // ================================
  private fun disconnect(result: MethodChannel.Result) {
    backgroundExecutor.execute {
      try {
        device = null
        thread?.interrupt()
        thread = null

        socket?.close()
        socket = null

        Handler(Looper.getMainLooper()).post {
          publishBluetoothStatus(0)
          result.success(true)
        }
      } catch (e: Exception) {
        Handler(Looper.getMainLooper()).post {
          result.error("disconnect_error", e.message, null)
        }
      }
    }
  }

  private fun write(result: MethodChannel.Result, message: String) {
    if (thread != null) {
      thread!!.write(message.toByteArray())
      result.success(true)
    } else result.error("write_impossible", "not connected", null)
  }

  private fun writeBytes(result: MethodChannel.Result, bytes: ByteArray) {
    if (thread != null) {
      thread!!.write(bytes)
      result.success(true)
    } else result.error("write_bytes_impossible", "not connected", null)
  }

  // ================================
  // SCANNING
  // ================================
  private fun startScan(result: MethodChannel.Result) {
    backgroundExecutor.execute {
      try {
        Handler(Looper.getMainLooper()).post {
          application.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
          ba.startDiscovery()
          result.success(true)
        }
      } catch (e: Exception) {
        Handler(Looper.getMainLooper()).post {
          result.error("scan_error", e.message, null)
        }
      }
    }
  }

  private fun stopScan(result: MethodChannel.Result) {
    backgroundExecutor.execute {
      try {
        Handler(Looper.getMainLooper()).post {
          ba.cancelDiscovery()
          result.success(true)
        }
      } catch (e: Exception) {
        Handler(Looper.getMainLooper()).post {
          result.error("stop_scan_error", e.message, null)
        }
      }
    }
  }

  // ================================
  // PERMISSIONS
  // ================================
  private val myPermissionCode = 34264

  private fun initPermissions(result: MethodChannel.Result) {
    activeResult = result
    checkPermissions(application)
  }

  private fun arePermissionsGranted(application: Context) {
    val permissions = mutableListOf(
      Manifest.permission.BLUETOOTH,
      Manifest.permission.BLUETOOTH_ADMIN,
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
      permissions.add(Manifest.permission.BLUETOOTH_SCAN)
    }

    permissionGranted = permissions.all {
      ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
    }
  }

  private fun checkPermissions(application: Context) {
    arePermissionsGranted(application)

    if (!permissionGranted) {
      val permissions = mutableListOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
      }
      ActivityCompat.requestPermissions(pluginActivity, permissions.toTypedArray(), myPermissionCode)
    } else completeCheckPermissions()
  }

  private fun completeCheckPermissions() {
    if (permissionGranted)
      activeResult?.success(true)
    else
      activeResult?.error("permissions_not_granted", "Bluetooth permissions required.", null)

    activeResult = null
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ): Boolean {
    if (requestCode == myPermissionCode) {
      permissionGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
      completeCheckPermissions()
      return true
    }
    return false
  }

  // ================================
  // BONDED DEVICES
  // ================================
  @SuppressLint("MissingPermission")
  fun getDevices(result: MethodChannel.Result) {
    backgroundExecutor.execute {
      try {
        val devices = ba.bondedDevices
        val list = devices.map {
          hashMapOf("address" to it.address, "name" to it.name)
        }
        Handler(Looper.getMainLooper()).post {
          result.success(list)
        }
      } catch (e: Exception) {
        Handler(Looper.getMainLooper()).post {
          result.error("get_devices_error", e.message, null)
        }
      }
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    backgroundExecutor.shutdown()
    channel.setMethodCallHandler(null)
  }
}
