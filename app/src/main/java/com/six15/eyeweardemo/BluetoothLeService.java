package com.six15.eyeweardemo;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BluetoothLeService extends Service {
    private final static String TAG = "BluetoothLeService";
    private final static BlockingQueue<byte[]> packetQueue = new LinkedBlockingQueue<>();
    private final static Lock qLock = new ReentrantLock();
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private Handler mHandler;
    private Handler mQueueHandler;
    private Thread mQueueProcessorThread = null;
    private boolean mScanning = false;
    private long SCAN_PERIOD = 5000;

    private static final String mDeviceName  = "SIX15.EYE";
    private boolean bInitialized = false;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String ACTION_DATA_WRITE_COMPLETED =
            "com.example.bluetooth.le.ACTION_WRITE_COMPLETED";
    public final static String ACTION_DEVICE_FOUND =
            "com.example.bluetooth.le.ACTION_DEVICE_FOUND";
    public final static String ACTION_BLE_SCAN_START =
            "com.example.bluetooth.le.ACTION_BLE_SCAN_START";
    public final static String ACTION_BLE_SCAN_STOP =
            "com.example.bluetooth.le.ACTION_BLE_SCAN_STOP";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(Six15GattAttributes.HEART_RATE_MEASUREMENT);

    public final static UUID UUID_SIX15_RECEIVE_DATA =
            UUID.fromString(Six15GattAttributes.SIX15_DATA_RX);

    public final static UUID UUID_SIX15_TRANSMIT_DATA =
            UUID.fromString(Six15GattAttributes.SIX15_DATA_TX);

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);

                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                // start processing queue
                processQueue();
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onReliableWriteCompleted (BluetoothGatt gatt, int status){
            if (status == BluetoothGatt.GATT_SUCCESS) {

            }
            else
            {

            }

            // process next item in queue (queue blocks if empty)
            processQueue();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status){
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_WRITE_COMPLETED, characteristic);

            } else {
                Log.w(TAG, "onCharacteristicWrite received: " + status);
            }

            // process next item in queue (queue blocks if empty)
            processQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else if(UUID_SIX15_RECEIVE_DATA.equals(characteristic.getUuid())) {
            Log.d(TAG, "Six15 Eyewear bytes.");
            final byte[] data = characteristic.getValue();
            // send to queue
            Log.i(TAG, "DATA: " + bytesToHex(data));
            intent.putExtra("BLE_DATA", data);

        } else if(UUID_SIX15_TRANSMIT_DATA.equals(characteristic.getUuid())) {

            final byte[] data = characteristic.getValue();
            Log.d(TAG, "TX: " + characteristic.getUuid() + " Data: " + bytesToHex(data));

            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
            }
        } else
        {
            // For all other profiles, writes the data formatted in HEX.

            Log.w(TAG, "Unknown service characteristic " + characteristic.getUuid());
            final byte[] data = characteristic.getValue();

            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
            }
        }
        sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    class Consumer implements Runnable {
        private final BlockingQueue<byte[]> queue;
        private final BluetoothGattCharacteristic sendDataChar;

        Consumer(BlockingQueue q, BluetoothGattCharacteristic writeChar) {
            queue = q;
            sendDataChar = writeChar;
        }

        public void run() {
            try {
                consume(queue.take());
            } catch (InterruptedException ex) {
                Log.e(TAG, "Packet Queue Interrupted: " + ex.getMessage());
            }
        }
        void consume(byte[] x) {
            sendDataChar.setValue(x);
            boolean status = writeCharacteristic(sendDataChar);
            if (!status)
                Log.e(TAG, "Failed to send BLE Packet");
        }
    }

    private void processQueue(){
        final BluetoothGattCharacteristic sendDataChar = mBluetoothGatt.getService(UUID.fromString(Six15GattAttributes.SIX15_BLE_SERVICE))
                .getCharacteristic(UUID.fromString(Six15GattAttributes.SIX15_DATA_TX));
        if(sendDataChar == null)
        {
            Log.e(TAG, "Fatal Error cannot retrieve characteristic from service");
            return;
        }
        Consumer c1 = new Consumer(packetQueue, sendDataChar);
        mQueueProcessorThread = new Thread(c1);
        mQueueProcessorThread.start();
    }

    @Override
    public void onCreate() {
        Log.d(TAG,"Create");
        mHandler = new Handler();
        mQueueHandler = new Handler();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroy");
    }
    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        Log.d(TAG,"Initialize");
        bInitialized = false;
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return bInitialized;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return bInitialized;
        }
        bInitialized = true;
        return bInitialized;
    }

    public boolean isInitialized(){
        return bInitialized;
    }

    public BluetoothDevice getConnectedDevice(){
        if(mBluetoothGatt != null && mConnectionState == STATE_CONNECTED){
            return mBluetoothGatt.getDevice();
        }

        return null;
    }
    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }
        return mBluetoothGatt.writeCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is the SIX-15 Eyewear Serial TX
        if(UUID_SIX15_RECEIVE_DATA.equals(characteristic.getUuid())) {

            Log.i(TAG, "Enabling receive data from eyewear");
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(Six15GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);

        }

    }

    public BluetoothGattService getBLEService(UUID uuid){
        BluetoothGattService temp = null;
        temp = mBluetoothGatt.getService(uuid);
        if(temp == null)
            mBluetoothGatt.discoverServices();

        return temp;
    }

    public void scanLeDevice(final boolean enable) {

        final BluetoothAdapter.LeScanCallback mLeScanCallback =
                new BluetoothAdapter.LeScanCallback() {

                    @Override
                    public void onLeScan(final BluetoothDevice device, final int rssi, byte[] scanRecord) {

                        if(device != null && device.getName() != null) {
                            if (device.getName().compareTo(mDeviceName) == 0) {
                                Log.i(TAG, "Found Six-15 Eyewear");
                                final Intent intent = new Intent(ACTION_DEVICE_FOUND);
                                intent.putExtra("BLE_DEVICE", device);
                                sendBroadcast(intent);
                            }
                        }else
                        {
                            Log.i(TAG,"Scanned device is null or has a null name");
                        }
                    }
                };


        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Timout Stop Scan");
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);

                    broadcastUpdate(ACTION_BLE_SCAN_STOP);
                }
            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
            broadcastUpdate(ACTION_BLE_SCAN_START);
        } else {

            Log.d(TAG, "Stopping Scan");
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
            broadcastUpdate(ACTION_BLE_SCAN_STOP);

        }
    }

    public boolean isScanning(){
        return mScanning;
    }

    public boolean isConnected(){
        if(mConnectionState == STATE_CONNECTED)
            return true;

        return false;
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }

    public boolean sendCommandString(String command){
        boolean bReturn = true;
        SerialProtocol protoHelper = new SerialProtocol(SerialProtocol.FrameTypes.STRING, command.length());
        byte[] byteArr = command.getBytes();
        qLock.lock();
        Log.d(TAG, "Add " + command + " to BLE queue");
        Log.d(TAG, "Queue Size: " + packetQueue.size());
        while (protoHelper.hasNextPacket()) {
            byte[] temp = protoHelper.getNextPacket(byteArr);
            packetQueue.add(temp);
        }
        qLock.unlock();
        return bReturn;
    }
}
