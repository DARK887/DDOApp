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
import android.os.IBinder;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * Created by ChristianSchulzendor on 05.08.2014.
 */
public class BluetoothLeService extends Service {

	public final static String ACTION_GATT_CONNECTED =
			"de.egh.dynamodrivenodometer.ACTION_GATT_CONNECTED";
	public final static String ACTION_GATT_DISCONNECTED =
			"de.egh.dynamodrivenodometer.ACTION_GATT_DISCONNECTED";
	public final static String ACTION_GATT_SERVICES_DISCOVERED =
			"de.egh.dynamodrivenodometer.ACTION_GATT_SERVICES_DISCOVERED";
	public final static String ACTION_VALUE_AVAILABLE =
			"de.egh.dynamodrivenodometer.ACTION_VALUE_AVAILABLE";
	public final static String ACTION_MESSAGE_AVAILABLE =
			"de.egh.dynamodrivenodometer.ACTION_MESSAGE_AVAILABLE";
	public final static String EXTRA_DATA =
			"de.egh.dynamodrivenodometer.EXTRA_DATA";
	private final static String TAG = BluetoothLeService.class.getSimpleName();
	private static final int STATE_DISCONNECTED = 0;
	private int mConnectionState = STATE_DISCONNECTED;
	private static final int STATE_CONNECTING = 1;
	private static final int STATE_CONNECTED = 2;
	private final IBinder mBinder = new LocalBinder();
	private boolean readJob;
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


			} else {
				//Stop reading value
				readJob = false;

				if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					intentAction = ACTION_GATT_DISCONNECTED;
					mConnectionState = STATE_DISCONNECTED;
					Log.i(TAG, "Disconnected from GATT server.");
					broadcastUpdate(intentAction);
				}
			}
		}


		private void broadcastUpdate(final String action) {
			final Intent intent = new Intent(action);
			sendBroadcast(intent);
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
			} else {
				Log.w(TAG, "onServicesDiscovered received: " + status);
			}
		}

		private String characteristicValue(BluetoothGattCharacteristic characteristic) {

			// For all other profiles, writes the data formatted in HEX.
			final byte[] data = characteristic.getValue();
			if (data != null && data.length > 0) {

				final StringBuilder sb = new StringBuilder(data.length);
				for (byte byteChar : data) {
					if (byteChar != 0x00) {
						char c = (char) (byteChar & 0xFF);
						sb.append(c);
					}
				}
				return sb.toString();

			}
		}

		private void broadcastUpdate(final String action,
		                             final BluetoothGattCharacteristic characteristic) {
			final Intent intent = new Intent(action);
		intent.putExtra(EXTRA_DATA, characteristicValue(characteristic));

			sendBroadcast(intent);
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt,
		                                 BluetoothGattCharacteristic characteristic,
		                                 int status) {
			Log.v(TAG, "onCharacteristicRead()");
			if (status == BluetoothGatt.GATT_SUCCESS) {
				Log.v(TAG, "Characteristic value: " + characteristic.getValue());
				processCharacteristic(characteristic);
				//broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
			}
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt,
		                                    BluetoothGattCharacteristic characteristic) {
		//	broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
		}
	};
	/**
	 * Analyses the value and updates storage and UI.
	 */
	private void processCharacteristic(BluetoothGattCharacteristic characteristic) {

		String message = null;
		String data = characteristic.getStringValue(0);

		if (data != null && data.length() > 0) {
//			Log.v(TAG, "Value >>>" + data + "<<<");

			//First character holds the type.
			char dataType = data.charAt(0);
			String dataContent = data.substring(1);

			//Distance value: Number of wheel rotation
			if (dataType == 'D') {

				long value;

				//Expecting the wheel rotation number as long value
				try {
					value = Long.valueOf(dataContent);
					dodDistanceValue = value;
					dodLastUpdateAt = System.currentTimeMillis();
//					Log.v(TAG, "Value=" + dodDistanceValue);

				} catch (Exception e) {
					Log.v(TAG, "Caught exception: Not a long value >>>" + dataContent + "<<<");
					message = "Not a number >>>" + dataContent + "<<<";
				}
			}
			// Message
			else if (dataType == 'M') {
				Log.v(TAG, "Received message: " + dataContent);
				message = dataContent;
			}

			//Unknown type
			else {
				Log.v(TAG, "Received value with unknown type: " + dataContent);
				message = "Unknown value:" + dataContent;
			}
		}

		// Invalid message
		else {
			Log.v(TAG, "Received invalid message >>>" + data + "<<<");
			message = "Invalid message:" + data;
		}

		if (message != null) {
			messageBox.add(message);
		}

	}

	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	private String mBluetoothDeviceAddress;
	private BluetoothGatt mBluetoothGatt;

	private void broadcastUpdate(final String action) {
		final Intent intent = new Intent(action);
		sendBroadcast(intent);
	}

	/**
	 * Connects to the GATT server hosted on the Bluetooth LE device.
	 *
	 * @param address The device address of the destination device.
	 * @return Return true if the connection is initiated successfully. The connection result
	 * <p/>
	 * is reported asynchronously through the
	 * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 * callback.
	 */
	public boolean connect(final String address) {
		Log.v(TAG, "connect()");

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
	 * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
	 * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
	 * callback.
	 *
	 * @param characteristic The characteristic to read from.
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
	 * @param characteristic Characteristic to act on.
	 * @param enabled        If true, enable notification.  False otherwise.
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
	 * Sends a notification to the GATT Server for the RFDUINO read characteristic
	 */
	public void sendValueReadNotification() {

		BluetoothGattCharacteristic chara = mBluetoothGatt.getService(UUID.fromString(Address.Rfduino.SERVICE)).getCharacteristic(
				UUID.fromString(Address.Rfduino.Characteristic.RECEIVE));

		Log.v(TAG, "sendValueReadNotification " + chara.getUuid() + " "+ chara.getValue());

		mBluetoothGatt.readCharacteristic(chara);
	}

	/**
	 * Retrieves a list of supported GATT services on the connected device. This should be
	 * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
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
	 * Disconnects an existing connection or cancel a pending connection. The disconnection result
	 * is reported asynchronously through the
	 * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 * callback.
	 */
	public void disconnect() {
		Log.v(TAG, "disconnect()");
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.disconnect();
	}

	/**
	 * Initializes a reference to the local Bluetooth adapter.
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

		return true;
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.v(TAG, "onBind()");
		return mBinder;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.v(TAG, "onCreate()");

	}

	/**
	 * After using a given BLE device, the app must call this method to ensure resources are
	 * released properly.
	 */
	public void close() {
		Log.v(TAG, "close()");
		if (mBluetoothGatt == null) {
			return;
		}
		mBluetoothGatt.close();
		mBluetoothGatt = null;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		// After using a given device, you should make sure that BluetoothGatt.close() is called
		// such that resources are cleaned up properly.  In this particular example, close() is
		// invoked when the UI is disconnected from the Service.
		close();
		return super.onUnbind(intent);
	}

	public abstract class Address {
		abstract class Rfduino {
			static final String SERVICE = "00002220-0000-1000-8000-00805f9b34fb";

			abstract class Characteristic {
				static final String RECEIVE = "00002221-0000-1000-8000-00805f9b34fb";
			}
		}

	}

	public class LocalBinder extends Binder {
		BluetoothLeService getService() {
			return BluetoothLeService.this;
		}
	}

}
