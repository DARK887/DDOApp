package de.egh.dynamodrivenodometer;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Base class for real and dummy device access. */
abstract class DeviceFascade {
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

	public class BTInitializeException extends Exception {
	}

	public static class RealDeviceFascade extends DeviceFascade{

		private BluetoothManager mBluetoothManager;
		private BluetoothAdapter mBluetoothAdapter;  // Implements callback methods for GATT events that the app cares about.  For example,

		/**
		 * @param service
		 * 		Hosted Service
		 */
		RealDeviceFascade(Service service) throws BTInitializeException {
			super(service);

			// For API level 18 and above, get a reference to BluetoothAdapter through
			// BluetoothManager.
			if (mBluetoothManager == null) {
				mBluetoothManager = (BluetoothManager) service.getSystemService(Context.BLUETOOTH_SERVICE);
				if (mBluetoothManager == null) {
					Log.e(TAG, "Unable to initialize BluetoothManager.");
					throw new BTInitializeException();
				}
			}

			mBluetoothAdapter = mBluetoothManager.getAdapter();
			if (mBluetoothAdapter == null) {
				Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
				throw new BTInitializeException();
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

		}

		@Override
		protected String getDeviceName() {
			return null;
		}

		@Override
		protected boolean isDemo() {
			return false;
		}
	}

	private final static String TAG = DeviceFascade.class.getSimpleName();

	protected Service service;
	/** True while in device scanning mode */
	protected boolean mScanning;

	/** TRUE, if connected to an device. */
	protected boolean mConnected;

	//TODO mDemo ? Constants.Devices.DUMMY_DEVICE : AppService.Constants.Devices.NAME
	 protected abstract String getDeviceName();

	//TODO return mDemo
protected abstract boolean isDemo();

	/**
	 * @param service
	 * 		Hosted Service
	 */
	DeviceFascade(Service service) {

		this.service = service;
	}

	/** Use this or the other broadcastUpdate method for broadcasting. */
	protected void broadcastUpdate(final String action) {
		Log.v(TAG, "broadcastUpdate " + action);
		final Intent intent = new Intent(action);
		broadcastUpdate(intent);
	}

	/** Use this or the other broadcastUpdate method for broadcasting. */
	protected void broadcastUpdate(Intent intent) {
		intent.putExtra(AppService.Constants.EXTRA_DEVICE_NAME, getDeviceName());
		intent.putExtra(AppService.Constants.EXTRA_IS_DEMO, isDemo());
		service.sendBroadcast(intent);
	}

}
