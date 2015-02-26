package de.egh.dynamodrivenodometer;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * Manage all connection stuff to a device or dummy device.
 */
public class AppService extends Service {


	private final static String TAG = AppService.class.getSimpleName();
	private final IBinder mBinder = new LocalBinder();
	private DeviceFacade deviceFacade;

	/**
	 * Sends a notification to the device (either the GATT Server for the RFDUINO read characteristic
	 * ot the mock devicve. Doesn't do anything, if not connected with an device.
	 *
	 * @return FALSE, if device lost / could not read. Otherwise true.
	 */
	public boolean readValue() {
//		Log.v(TAG, "readValue() for " + deviceAddress);
		if(deviceFacade != null)
		return deviceFacade.readValue();
else
			return false;
	}

	/**
	 * Disconnects an existing connection or cancel a pending connection. The disconnection result is
	 * reported asynchronously through the {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt,
	 *int, int)} callback. Nothing happens, if no device is connected. Supports BT device and dummy
	 * device. Use connect() to (re-)connect to same or other device.
	 */
	public void closeDeviceConnection() {
//		Log.v(TAG, "close()for device " + deviceAddress);

		if (deviceFacade != null) {
			deviceFacade.close();
			deviceFacade = null;
		}
	}

	@Override
	public void onDestroy() {
		Log.v(TAG, "onDestroy()");

		if (deviceFacade != null) {
			deviceFacade.close();
			deviceFacade = null;
		}
		super.onDestroy();
	}

	/**
	 * Does nothing, if no device is initialized
	 */
	public boolean broadcastStatus() {


		if (deviceFacade != null) {
			deviceFacade.broadcastStatus();
		}

		return true;
	}  // Device scan callback.

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
	public boolean scanDevice(boolean demo) {
		Log.v(TAG, "scanDevice()  " + demo);

		if (deviceFacade != null) {
			deviceFacade.close();
			deviceFacade = null;
		}

		if (demo) {
			deviceFacade = new DeviceFacade.DummyDeviceFacade(this);
		} else {
			try {
				deviceFacade = new DeviceFacade.RealDeviceFacade(this);
			} catch (DeviceFacade.BTInitializeException e) {
				e.printStackTrace();
				return false;
			}
		}
		deviceFacade.scan();


		return true;
	}

	/**
	 * After using a given BLE device, the app must call this method to ensure resources are released
	 * properly.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		Log.v(TAG, "onCreate()");


	}

	@Override
	public boolean onUnbind(Intent intent) {
		Log.v(TAG, "onUnbind( ) ");

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
		public final static String EXTRA_TIMESTAMP =
				"de.egh.dynamodrivenodometer.EXTRA_TIMESTAMP";

//		abstract class SharedPrefs {
//			static final String NAME = "DOD";
//			static final String DEMO = "DEMO";
//		}

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
			//		static final String SEND = "00002222-0000-1000-8000-00805f9b34fb"; //http://evothings.com/forum/viewtopic.php?f=8&t=102
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
