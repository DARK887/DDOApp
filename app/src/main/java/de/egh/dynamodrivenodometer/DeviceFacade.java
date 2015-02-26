package de.egh.dynamodrivenodometer;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;

/** Base class for real and dummy device access. */
abstract class DeviceFacade {

	protected Service service;
	/** True while in device scanning mode */
	protected boolean scanning;
	/** TRUE, if connected to an device. */
	protected boolean connected;
	/** Can be null if not connected or Constants.Devices.DUMMY_DEVICE for dummy device */
	protected String deviceAddress;


	/**
	 * Sends a notification to the device (either the GATT Server for the RFDUINO read characteristic
	 * ot the mock devicve. Doesn't do anything, if not connected with an device.
	 *
	 * @return FALSE, if device lost / could not read. Otherwise true.
	 */
	public abstract boolean readValue();

	public DeviceFacade(Service service) {
		this.service = service;
		this.connected = false;
		this.scanning = false;
		this.deviceAddress = null;
	}

	public abstract String getTag();

	/** Scan for device. */
	public abstract void scan();

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
	protected abstract boolean connect(final String address);

	/** Static name of the device. */
	protected abstract String getDeviceName();

	/** Convenience method for instance of. */
	protected abstract boolean isDemo();

	/**
	 * Disconnects an existing connection or cancel a pending connection. The disconnection result is
	 * reported asynchronously through the {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt,
	 *int, int)} callback. Nothing happens, if no device is connected. Supports BT device and dummy
	 * device. Use connect() to (re-)connect to same or other device.
	 */
	public abstract void close();

	/** Send actual status to app */
	public void broadcastStatus() {
		broadcastUpdate(AppService.Constants.Actions.DEVICE);

		if (connected) {
			broadcastUpdate(AppService.Constants.Actions.CONNECTED);
		} else {
			broadcastUpdate(AppService.Constants.Actions.NOT_CONNECTED);
		}

		if (scanning) {
			broadcastUpdate(AppService.Constants.Actions.SCANNING);
		}

	}

	/** Use this or the other broadcastUpdate method for broadcasting. */
	protected void broadcastUpdate(final String action) {
		//	Log.v(getTag(), "broadcastUpdate " + action);
		final Intent intent = new Intent(action);
		broadcastUpdate(intent);
	}

	/** Use this or the other broadcastUpdate method for broadcasting. */
	protected void broadcastUpdate(Intent intent) {

		intent.putExtra(AppService.Constants.EXTRA_DEVICE_NAME, getDeviceName());
		intent.putExtra(AppService.Constants.EXTRA_IS_DEMO, isDemo());
		intent.putExtra(AppService.Constants.EXTRA_TIMESTAMP, System.currentTimeMillis());
		service.sendBroadcast(intent);
	}

	/** Simulating of device that sends distance values. */
	public static class DummyDeviceFacade extends DeviceFacade {

		public DummyDeviceFacade(Service service) {
			super(service);
			broadcastStatus();
		}

		@Override
		public String getTag() {
			return DummyDeviceFacade.class.getSimpleName();
		}

		@Override
		public boolean readValue() {
			final Intent intent = new Intent(AppService.Constants.Actions.DATA_AVAILABLE);
			intent.putExtra(AppService.Constants.EXTRA_DATA, AppService.Constants.Datatypes.DISTANCE + String.valueOf(System.currentTimeMillis()));
			broadcastUpdate(intent);
			return true;
		}

		@Override
		public void scan() {
			// Automatically connects to the device upon successful start-up initialization.
			//dodDevice = null;
			//Don't send 'Scanning' here (broadcast can be passed by the next one)
			scanning = true;
			broadcastUpdate(AppService.Constants.Actions.SCANNING);
			connect(AppService.Constants.Devices.DUMMY_DEVICE);
		}

		@Override
		protected boolean connect(String address) {

			if (address == null) {
				return false;
			}
			deviceAddress = address;
			connected = true;
			broadcastUpdate(AppService.Constants.Actions.CONNECTED);
			return true;
		}

		@Override
		protected String getDeviceName() {
			return AppService.Constants.Devices.DUMMY_DEVICE;
		}

		@Override
		protected boolean isDemo() {
			return true;
		}

		@Override
		public void close() {
			Log.v(getTag(), "close() ");
			scanning = false;
			deviceAddress = null;
		}
	}

	/** Can connect to a device via BTLE */
	public static class RealDeviceFacade extends DeviceFacade {
		@Override
		public String getTag() {
			return RealDeviceFacade.class.getSimpleName();
		}

		// connection change and services discovered.
		private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {


			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

				switch (newState) {
					case BluetoothProfile.STATE_CONNECTED:
						Log.i(getTag(), "Gatt: STATE_CONNECTED");
						// Attempts to discover services after successful connection.
						Log.i(getTag(), "Attempting to start service discovery:" +
								bluetoothGatt.discoverServices());
						break;
					case BluetoothProfile.STATE_CONNECTING:
						Log.i(getTag(), "Gatt: STATE_CONNECTING");
						break;
					case BluetoothProfile.STATE_DISCONNECTING:
						Log.i(getTag(), "Gatt: STATE_DISCONNECTING");
						break;
					case BluetoothProfile.STATE_DISCONNECTED:
						Log.i(getTag(), "Gatt: STATE_DISCONNECTED");
						broadcastUpdate(AppService.Constants.Actions.NOT_CONNECTED);
						break;
					default:
						Log.i(getTag(), "Unknown BluetoothProfile state=" + newState);
						break;
				}
			}

			@Override
			/** Called only when status == BluetoothGatt.GATT_SUCCESS */
			public void onServicesDiscovered(BluetoothGatt gatt, int status) {
				Log.v(getTag(), "onServicesDiscovered()");

				if (status != BluetoothGatt.GATT_SUCCESS) {
					Log.w(getTag(), "Unexpected status= " + status);
					return;
				}

				connected = true;
				broadcastUpdate(AppService.Constants.Actions.CONNECTED);
			}

			private void broadcastCharacteristic(final String action,
			                                     final BluetoothGattCharacteristic characteristic) {
				final Intent intent = new Intent(action);

				// For all other profiles, writes the data formatted in HEX.
				final byte[] data = characteristic.getValue();
				if (data != null && data.length > 0) {

					Log.v(getTag(), "broadcastCharacteristic Byte[] data=" + Arrays.toString(data)); //Should be "D" + <Distance in mm>
					final StringBuilder sb = new StringBuilder(data.length);
					for (byte byteChar : data) {
						if (byteChar != 0x00) {
							char c = (char) (byteChar & 0xFF);
							sb.append(c);
						}
					}
					Log.v(getTag(), "broadcastCharacteristic String=" + sb.toString()); //Should be "D" + <Distance in mm>
					intent.putExtra(AppService.Constants.EXTRA_DATA, sb.toString());
					broadcastUpdate(intent);
				}
			}

			@Override
			public void onCharacteristicRead(BluetoothGatt gatt,
			                                 BluetoothGattCharacteristic characteristic,
			                                 int status) {
				Log.v(getTag(), "onCharacteristicRead()");
				if (status == BluetoothGatt.GATT_SUCCESS) {
					Log.v(getTag(), "Characteristic value: " + Arrays.toString(characteristic.getValue()));
					broadcastCharacteristic(AppService.Constants.Actions.DATA_AVAILABLE, characteristic);
				}
			}

			@Override
			public void onCharacteristicChanged(BluetoothGatt gatt,
			                                    BluetoothGattCharacteristic characteristic) {
				broadcastCharacteristic(AppService.Constants.Actions.DATA_AVAILABLE, characteristic);
			}
		};
		//For timer-driven actions
		private Handler handler;
		/** Stops scan after given period */
		private Runnable runnableStopScan;
		private BluetoothManager bluetoothManager;
		private BluetoothAdapter bluetoothAdapter;  // Implements callback methods for GATT events that the app cares about.  For example,
		/** Access to GATT */
		private BluetoothGatt bluetoothGatt;
		/** The connected device */
		private BluetoothDevice dodDevice;
		private BluetoothDevice bTDevice;

		@Override
		public boolean readValue() {
			Log.v(getTag(), "readValue() for " + deviceAddress);
			//Check precondition
			if (deviceAddress == null) {
				return false;
			}

			//First take care, that the device is already there
			if (bluetoothManager.getConnectionState(bTDevice, BluetoothGatt.GATT) == BluetoothProfile.STATE_CONNECTED) {
//		if (bluetoothGatt.getConnectionState(bTDevice) != BluetoothProfile.STATE_CONNECTED) {
				BluetoothGattCharacteristic chara = bluetoothGatt.getService(UUID.fromString(AppService.Constants.Address.Rfduino.SERVICE)).getCharacteristic(
						UUID.fromString(AppService.Constants.Address.Rfduino.Characteristic.RECEIVE));

				Log.v(getTag(), "readValue " + chara.getUuid() + " " + Arrays.toString(chara.getValue()));

				bluetoothGatt.readCharacteristic(chara);
			}
			// Device lost
			else {
				Log.v(getTag(), "Device lost. State=" + bluetoothManager.getConnectionState(bTDevice, BluetoothGatt.GATT));
				broadcastUpdate(AppService.Constants.Actions.NOT_CONNECTED);
				return false;
			}
			return true;

		}

		/**
		 * @param service
		 * 		Hosted Service
		 */
		RealDeviceFacade(Service service) throws BTInitializeException {
			super(service);
			//Handler for ongoing processes
			handler = new Handler();
			// For API level 18 and above, get a reference to BluetoothAdapter through
			// BluetoothManager.
			if (bluetoothManager == null) {
				bluetoothManager = (BluetoothManager) service.getSystemService(Context.BLUETOOTH_SERVICE);
				if (bluetoothManager == null) {
					Log.e(getTag(), "Unable to broadcastStatus BluetoothManager.");
					throw new BTInitializeException();
				}
			}

			bluetoothAdapter = bluetoothManager.getAdapter();
			if (bluetoothAdapter == null) {
				Log.e(getTag(), "Unable to obtain a BluetoothAdapter.");
				throw new BTInitializeException();
			}

			broadcastStatus();

		}

		private ScanCallback leScanCallback2 = new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				Log.d(RealDeviceFacade.this.getTag(), "onScanResult()");
				Log.d(RealDeviceFacade.this.getTag(), "result="+result);
				Log.d(RealDeviceFacade.this.getTag(), "callbackType="+callbackType);
				Log.d(RealDeviceFacade.this.getTag(), "Device.getName="+result.getDevice().getName());
				Log.d(RealDeviceFacade.this.getTag(), "Device.getAdapter="+result.getDevice().getAddress());
				Log.d(RealDeviceFacade.this.getTag(), "Device.getType="+result.getDevice().getType());

				//Connect to the RFDuino!
				if (result.getDevice().getName().equals(AppService.Constants.Devices.NAME)) {

					dodDevice = result.getDevice();
					scanning = false;
					handler.removeCallbacks(runnableStopScan);
					bluetoothAdapter.getBluetoothLeScanner().stopScan(leScanCallback2);

					// Automatically connects to the device upon successful start-up initialization.
					connect(dodDevice.getAddress());
				}
				super.onScanResult(callbackType, result);
			}

			@Override
			public void onScanFailed(int errorCode) {
				Log.d(RealDeviceFacade.this.getTag(), "onScanFailed errorCode="+errorCode);
				scanning = false;
				dodDevice = null;
				broadcastUpdate(AppService.Constants.Actions.NOT_CONNECTED);
				super.onScanFailed(errorCode);
			}
		};

//		private BluetoothAdapter.LeScanCallback leScanCallback =
//				new BluetoothAdapter.LeScanCallback() {
//
//					@Override
//					public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
//
//						Log.v(getTag(), "LeScanCallback.onLeScan found " + device.toString());
//
//						//Connect to the RFDuino!
//						if (device.getName().equals(AppService.Constants.Devices.NAME)) {
//
//							dodDevice = device;
//							scanning = false;
//							handler.removeCallbacks(runnableStopScan);
//							bluetoothAdapter.stopLeScan(leScanCallback);
//
//							// Automatically connects to the device upon successful start-up initialization.
//							connect(dodDevice.getAddress());
//						}
//
//					}
//				};


		/** Scans, if not still scanning. */
		@Override
		public void scan() {

			if (scanning) {
				return;
			}

			scanning = true;

			//Delete active broadcast
			handler.removeCallbacks(runnableStopScan);

			broadcastUpdate(AppService.Constants.Actions.SCANNING);

			runnableStopScan = new Runnable() {
				@Override
				public void run() {
					if (scanning) {
						Log.v(getTag(), "End of scan period: Stopping scan.");
						scanning = false;
//						bluetoothAdapter.stopLeScan(leScanCallback);
						bluetoothAdapter.getBluetoothLeScanner().stopScan(leScanCallback2);
						broadcastUpdate(AppService.Constants.Actions.NOT_CONNECTED);
					}
				}
			};
			// Stops scanning after a pre-defined scan period.
			handler.postDelayed(runnableStopScan, AppService.Constants.Bluetooth.SCAN_PERIOD);

			bluetoothAdapter.getBluetoothLeScanner().startScan(leScanCallback2);
//			bluetoothAdapter.startLeScan(leScanCallback);
		}

		@Override
		protected boolean connect(String address) {
			Log.v(getTag(), "connect() " + address);

			if (address == null) {
				Log.w(getTag(), "Missing address. Leave connect()");
				return false;
			}
			deviceAddress = address;

			Log.v(getTag(), "connecting BT device");

			if (bluetoothAdapter == null) {
				Log.w(getTag(), "BluetoothAdapter not initialized or unspecified address.");
				return false;
			}

			// Previously connected device.  Try to reconnect.
			if (deviceAddress != null && address.equals(deviceAddress)
					&& bluetoothGatt != null) {
				Log.d(getTag(), "Trying to use an existing bluetoothGatt for connection.");
				return bluetoothGatt.connect();
			}

			bTDevice = bluetoothAdapter.getRemoteDevice(address);

			// We want to directly connect to the device, so we are setting the autoConnect
			// parameter to false.
			bluetoothGatt = bTDevice.connectGatt(service, false, gattCallback);
			Log.d(getTag(), "Trying to create a new connection.");

			return true;
		}

		@Override
		protected String getDeviceName() {
			return AppService.Constants.Devices.NAME;
		}

		@Override
		protected boolean isDemo() {
			return false;
		}

		@Override
		public void close() {
			Log.v(getTag(), "close()for device " + deviceAddress);

			if (scanning) {
				handler.removeCallbacks(runnableStopScan);
				bluetoothAdapter.getBluetoothLeScanner().stopScan(leScanCallback2);
//				bluetoothAdapter.stopLeScan(leScanCallback);
				scanning = false;
			}

			if (bluetoothGatt != null) {
				bluetoothGatt.close();
				bluetoothGatt = null;
			}



//			if (bluetoothAdapter == null) {
//				Log.v(getTag(), "BluetoothAdapter not initialized");
//				return;
//			}
//
//      if(scanning)
//  			bluetoothAdapter.stopLeScan(leScanCallback);
//
//			if (bluetoothGatt == null) {
//				Log.v(getTag(), "BluetoothGatt not initialized");
//				return;
//			}
//
//			bluetoothGatt.closeDeviceConnection();
//
//			scanning = false;
//			if (bluetoothGatt != null) {
//				bluetoothGatt.close();
//				bluetoothGatt = null;
//			}
//
//			deviceAddress = null;
		}


	}

	public class BTInitializeException extends Exception {
	}

}
