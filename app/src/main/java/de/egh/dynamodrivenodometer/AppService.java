package de.egh.dynamodrivenodometer;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Manage all connection stuff to a device or dummy device.
 */
public class AppService extends Service {


	private final static String TAG = AppService.class.getSimpleName();
	private final IBinder mBinder = new LocalBinder();
	/** Is TRUE, when mock device is used */
	boolean mDemo;
	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;  // Implements callback methods for GATT events that the app cares about.  For example,
	private String mDeviceAddress;
	/** Access to GATT */
	private BluetoothGatt mBluetoothGatt;
	//For timer-driven actions
	private Handler mHandler;
	private BluetoothDevice mBTDevice;
	/** Stops scan after given period */
	private Runnable runnableStopScan;
	/** True while in device scanning mode */
	private boolean mScanning;
	/** The connected device */
	private BluetoothDevice dodDevice;
	/** TRUE, if connected to an device. */
	private boolean mConnected;
	// connection change and services discovered.
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

			switch (newState) {
				case BluetoothProfile.STATE_CONNECTED:
					Log.i(TAG, "Gatt: STATE_CONNECTED");
					// Attempts to discover services after successful connection.
					Log.i(TAG, "Attempting to start service discovery:" +
							mBluetoothGatt.discoverServices());
					break;
				case BluetoothProfile.STATE_CONNECTING:
					Log.i(TAG, "Gatt: STATE_CONNECTING");
					break;
				case BluetoothProfile.STATE_DISCONNECTING:
					Log.i(TAG, "Gatt: STATE_DISCONNECTING");
					break;
				case BluetoothProfile.STATE_DISCONNECTED:
					Log.i(TAG, "Gatt: STATE_DISCONNECTED");
					AppService.this.broadcastUpdate(Constants.Actions.NOT_CONNECTED);
					break;
				default:
					Log.i(TAG, "Unknown BluetoothProfile state=" + newState);
					break;

			}
		}

		@Override
		/** Called only when status == BluetoothGatt.GATT_SUCCESS */
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			Log.v(TAG, "onServicesDiscovered()");

			if (status != BluetoothGatt.GATT_SUCCESS) {
				Log.w(TAG, "Unexpected status= " + status);
				return;
			}

			mConnected = true;
			AppService.this.broadcastUpdate(Constants.Actions.CONNECTED);
		}

		private void broadcastCharacteristic(final String action,
		                                     final BluetoothGattCharacteristic characteristic) {
			final Intent intent = new Intent(action);

			// For all other profiles, writes the data formatted in HEX.
			final byte[] data = characteristic.getValue();
			if (data != null && data.length > 0) {

				Log.v(TAG, "broadcastCharacteristic Byte[] data=" + Arrays.toString(data)); //Should be "D" + <Distance in mm>
				final StringBuilder sb = new StringBuilder(data.length);
				for (byte byteChar : data) {
					if (byteChar != 0x00) {
						char c = (char) (byteChar & 0xFF);
						sb.append(c);
					}
				}
				Log.v(TAG, "broadcastCharacteristic String=" + sb.toString()); //Should be "D" + <Distance in mm>
				intent.putExtra(Constants.EXTRA_DATA, sb.toString());
        AppService.this.broadcastUpdate(intent);
			}
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt,
		                                 BluetoothGattCharacteristic characteristic,
		                                 int status) {
			Log.v(TAG, "onCharacteristicRead()");
			if (status == BluetoothGatt.GATT_SUCCESS) {
				Log.v(TAG, "Characteristic value: " + Arrays.toString(characteristic.getValue()));
				broadcastCharacteristic(Constants.Actions.DATA_AVAILABLE, characteristic);
			}
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt,
		                                    BluetoothGattCharacteristic characteristic) {
			broadcastCharacteristic(Constants.Actions.DATA_AVAILABLE, characteristic);
		}

	};

/** Use this or the other broadcastUpdate method for broadcasting. */
	private void broadcastUpdate(final String action) {
		Log.v(TAG, "broadcastUpdate " + action);
		final Intent intent = new Intent(action);
		broadcastUpdate(intent);
	}

	/** Use this or the other broadcastUpdate method for broadcasting. */
	private void broadcastUpdate(Intent intent){
		intent.putExtra(Constants.EXTRA_DEVICE_NAME, mDemo ? Constants.Devices.DUMMY_DEVICE : Constants.Devices.NAME);
		intent.putExtra(Constants.EXTRA_IS_DEMO, mDemo);
		sendBroadcast(intent);
	}

	/**
	 * Connects to the GATT server hosted on the Bluetooth LE device. To mock a device, it can also
	 * connect to dummy device.
	 *
	 * @param address
	 * 		The device address of the destination device or Constants.DUMMY_DEVICE for mocking.
	 * @return Return true if the connection is initiated successfully. The connection result
	 * <p/>
	 * is reported asynchronously through the {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt,
	 *int, int)} callback.
	 */
	private boolean connect(final String address) {
		Log.v(TAG, "connect() " + address);

		disconnect();

		if (address == null) {
			return false;
		}
		mDeviceAddress = address;

		//Dummy mode
		if (address.equals(Constants.Devices.DUMMY_DEVICE)) {
			Log.v(TAG, "connecting Dummy device");


			// Dummy device is super fast
			// Broadcast this
			mBluetoothGatt = null;
			mConnected = true;

			broadcastUpdate(Constants.Actions.CONNECTED);
			return true;
		}

		//Normal device mode
		Log.v(TAG, "connecting BT device");

		if (mBluetoothAdapter == null) {
			Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
			return false;
		}

		// Previously connected device.  Try to reconnect.
		if (mDeviceAddress != null && address.equals(mDeviceAddress)
				&& mBluetoothGatt != null) {
			Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
			return mBluetoothGatt.connect();
		}

		mBTDevice = mBluetoothAdapter.getRemoteDevice(address);

		// We want to directly connect to the device, so we are setting the autoConnect
		// parameter to false.
		mBluetoothGatt = mBTDevice.connectGatt(this, false, mGattCallback);
		Log.d(TAG, "Trying to create a new connection.");

		return true;
	}

	/**
	 * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
	 * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt,
	 *android.bluetooth.BluetoothGattCharacteristic, int)} callback.
	 *
	 * @param characteristic
	 * 		The characteristic to read from.
	 */
	private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.readCharacteristic(characteristic);
	}

	/**
	 * Enables or disables notification on a give characteristic.
	 *
	 * @param characteristic
	 * 		Characteristic to act on.
	 * @param enabled
	 * 		If true, enable notification.  False otherwise.
	 */
	private void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
	                                           boolean enabled) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

	}

	/**
	 * Sends a notification to the device (either the GATT Server for the RFDUINO read characteristic
	 * ot the mock devicve. Doesn't do anything, if not connected with an device.
	 *
	 * @return FALSE, if device lost / could not read. Otherwise true.
	 */
	public boolean readValue() {
		Log.v(TAG, "readValue() for " + mDeviceAddress);
		//Check precondition
		if (mDeviceAddress == null) {
			return false;
		}

		// mock it by sending actual time as distance value
		if (mDeviceAddress.equals(Constants.Devices.DUMMY_DEVICE)) {
			final Intent intent = new Intent(Constants.Actions.DATA_AVAILABLE);
			intent.putExtra(Constants.EXTRA_DATA, Constants.Datatypes.DISTANCE + String.valueOf(System.currentTimeMillis()));
			broadcastUpdate(intent);
			return true;
		}

		// Real device


		//First take care, that the device is already there
		if (mBluetoothManager.getConnectionState(mBTDevice, BluetoothGatt.GATT) == BluetoothProfile.STATE_CONNECTED) {
//		if (mBluetoothGatt.getConnectionState(mBTDevice) != BluetoothProfile.STATE_CONNECTED) {
			BluetoothGattCharacteristic chara = mBluetoothGatt.getService(UUID.fromString(Constants.Address.Rfduino.SERVICE)).getCharacteristic(
					UUID.fromString(Constants.Address.Rfduino.Characteristic.RECEIVE));

			Log.v(TAG, "readValue " + chara.getUuid() + " " + Arrays.toString(chara.getValue()));

			mBluetoothGatt.readCharacteristic(chara);
		}
		// Device lost
		else {
			Log.v(TAG, "Device lost. State=" + mBluetoothManager.getConnectionState(mBTDevice, BluetoothGatt.GATT));
			broadcastUpdate(Constants.Actions.NOT_CONNECTED);
			return false;
		}
		return true;
	}

//	/** Let the service alive for a while after unbinding */
//	private Runnable runnableAlive;

	/**
	 * Retrieves a list of supported GATT services on the connected device. This should be invoked
	 * only after {@code BluetoothGatt#discoverServices()} completes successfully.
	 *
	 * @return A {@code List} of supported services.
	 */
	public List<BluetoothGattService> getSupportedGattServices() {
		if (mBluetoothGatt == null) {
			return null;
		}

		return mBluetoothGatt.getServices();
	}

	/**
	 * Disconnects an existing connection or cancel a pending connection. The disconnection result is
	 * reported asynchronously through the {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt,
	 *int, int)} callback. Nothing happens, if no device is connected. Supports BT device and dummy
	 * device. Use connect() to (re-)connect to same or other device.
	 */
	public void disconnect() {
		Log.v(TAG, "disconnect()for device " + mDeviceAddress);


//		// Nothing to disconnect
//		if (mDeviceAddress == null) {
//			return;
//		}


		// Dummy device
		if (mDeviceAddress != null && mDeviceAddress.equals(Constants.Devices.DUMMY_DEVICE)) {
			mDeviceAddress = null;
			return;
		}

		// BT device
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.disconnect();

		// Keine Ahnung, ob hiermit der GattCallback tot gemacht wird oder ob der
		// Disconnectaufruf noch kommt.
		closeGatt();
		mDeviceAddress = null;
	}

	@Override
	public void onDestroy() {
		Log.v(TAG, "onDestroy()");

		closeGatt();
		disconnect();
		super.onDestroy();
	}

	/**
	 * Initializes a reference to the local Bluetooth adapter. Call this after app binding.
	 *
	 * @return Return true if the initialization is successful.
	 */
	public boolean initialize() {
		// For API level 18 and above, get a reference to BluetoothAdapter through
		// BluetoothManager.
		if (mBluetoothManager == null) {
			mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager == null) {
				Log.e(TAG, "Unable to initialize BluetoothManager.");
				return false;
			}
		}

		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if (mBluetoothAdapter == null) {
			Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
			return false;
		}

		//Send actual status to app
		broadcastUpdate(Constants.Actions.DEVICE);

		if (mConnected) {
			broadcastUpdate(Constants.Actions.CONNECTED);
		} else {
			broadcastUpdate(Constants.Actions.NOT_CONNECTED);
		}

		if (mScanning) {
			broadcastUpdate(Constants.Actions.SCANNING);
		}


		return true;
	}  // Device scan callback.

	private BluetoothAdapter.LeScanCallback mLeScanCallback =
			new BluetoothAdapter.LeScanCallback() {

				@Override
				public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {

					Log.v(TAG, "LeScanCallback.onLeScan found " + device.toString());

					//Connect to the RFDuino!
					if (device.getName().equals(Constants.Devices.NAME)) {

						dodDevice = device;
						mScanning = false;
						mHandler.removeCallbacks(runnableStopScan);
						mBluetoothAdapter.stopLeScan(mLeScanCallback);

						// Automatically connects to the device upon successful start-up initialization.
						connect(dodDevice.getAddress());
					}

				}
			};

	@Override
	public IBinder onBind(Intent intent) {
		Log.v(TAG, "onBind()");
		//Remove killer
//		mHandler.removeCallbacks(runnableAlive);
		return mBinder;
	}

	/**
	 * Call this to start the scan mode.
	 */
	public void scanDevice(boolean demo) {
		Log.v(TAG, "scanDevice() " + demo);

		mScanning = true;
		mDemo = demo;

		//Delete active broadcast
		mHandler.removeCallbacks(runnableStopScan);

		if (demo) {

			// Automatically connects to the device upon successful start-up initialization.
			dodDevice = null;
			//Don't send 'Scanning' here (broadcast can be passed by the next one)
		connect(Constants.Devices.DUMMY_DEVICE);

		} else

		{
			broadcastUpdate(Constants.Actions.SCANNING);
			runnableStopScan = new Runnable() {
				@Override
				public void run() {
					if (mScanning) {
						Log.v(TAG, "End of scan period: Stopping scan.");
						mScanning = false;
						mBluetoothAdapter.stopLeScan(mLeScanCallback);
						broadcastUpdate(Constants.Actions.NOT_CONNECTED);
					}
				}
			};

			// Stops scanning after a pre-defined scan period.
			mHandler.postDelayed(runnableStopScan, Constants.Bluetooth.SCAN_PERIOD);

			mBluetoothAdapter.startLeScan(mLeScanCallback);

		}

	}

	/**
	 * After using a given BLE device, the app must call this method to ensure resources are released
	 * properly.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		Log.v(TAG, "onCreate()");

		//Handler for ongoing processes
		mHandler = new Handler();


		mDemo = getSharedPreferences(Constants.SharedPrefs.NAME, 0).getBoolean(Constants.SharedPrefs.DEMO, true);
		mScanning = false;
		mConnected = false;
	}

	/** Close BT Gatt client connection to device */
	private void closeGatt() {
		Log.v(TAG, "closeGatt()");

		mScanning = false;
		if (mBluetoothGatt == null) {
			return;
		}

		mBluetoothGatt.close();
		mBluetoothGatt = null;

	}

//	@Override
//	public int onStartCommand(final Intent intent, final int flags,
//	                          final int startId) {
//		Log.v(TAG, "onStartCommand( ) ");
//
//		// We don't want this service to continue running after canceled by the
//		// system.
//		return START_NOT_STICKY;
//	}

	@Override
	public boolean onUnbind(Intent intent) {
		Log.v(TAG, "onUnbind( ) ");

//		runnableAlive = new Runnable() {
//			@Override
//			public void run(){
//				//Kill yourself
//				Log.v(TAG, "Kill service now... ");
//				AppService.this.stopSelf();
//			}
//		};
//
//		// Stops service after a small period of time
//		mHandler.postDelayed(runnableAlive, Constants.Service.ALIVE_MS);
		return super.onUnbind(intent);
	}

	/** Constants for the service consumer to use. */
	public abstract class Constants {

		public final static String EXTRA_DATA =
				"de.egh.dynamodrivenodometer.EXTRA_DATA";
		public final static String EXTRA_DEVICE_NAME =
				"de.egh.dynamodrivenodometer.EXTRA_DEVICE_NAME";
		public final static String EXTRA_IS_DEMO =
				"de.egh.dynamodrivenodometer.EXTRA_IS_DEMO";

		abstract class SharedPrefs {
			static final String NAME = "DOD";
			static final String DEMO = "DEMO";
		}

		public abstract class Devices {
			public static final String DUMMY_DEVICE = "DUMMY_DEVICE";
			static final String NAME = "DDO";
		}

		/** Prexif values of payload content received by the device. */
		public abstract class Datatypes {
			public static final String DISTANCE = "D";
			public static final String MESSAGE = "M";
		}

		/** Broadcast events for the service consumer */
		public abstract class Actions {

			/** Sends new data from the connected device */
			public final static String DATA_AVAILABLE =
					"de.egh.dynamodrivenodometer.DATA_AVAILABLE";

			/** Not connected to device's Gatt server */
			public final static String NOT_CONNECTED =
					"de.egh.dynamodrivenodometer.NOT_CONNECTED";

			/** GATT Services available. Device can now be used. */
			public final static String CONNECTED =
					"de.egh.dynamodrivenodometer.CONNECTED";

			/** Scanning for devices */
			public final static String SCANNING =
					"de.egh.dynamodrivenodometer.Actions.SCANNING";

			/** Type of device */
			public final static String DEVICE =
					"de.egh.dynamodrivenodometer.Actions.DEVICE";

		}

		/** GUIDs */
		abstract class Address {

			/** RFDuino GUIDs */
			abstract class Rfduino {

				static final String SERVICE = "00002220-0000-1000-8000-00805f9b34fb";

				abstract class Characteristic {
					static final String RECEIVE = "00002221-0000-1000-8000-00805f9b34fb";
				}
			}
		}

		/** Bluetooth settings */
		abstract class Bluetooth {
			//Max. Duration im milliseconds to scan for the ddo device
			static final long SCAN_PERIOD = 3000;

		}

	}

	public class LocalBinder extends Binder {
		public AppService getService() {
			return AppService.this;
		}
	}


}
