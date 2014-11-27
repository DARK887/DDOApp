package de.egh.dynamodrivenodometer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;


public class MyActivity extends Activity {


	// This name must be set in the RFDuino
	private static final String TAG = MyActivity.class.getSimpleName();
	//Last time a distance value has been received as timestamp
	private long dodLastUpdateAt;
	//Distance in meters
	private long dodDistanceValue;
	//Messages from the device
	private MessageBox messageBox;
	private SimpleDateFormat sdf = new SimpleDateFormat("dd.MM HH:mm:ss");
	private TextView distanceValueView;
	private TextView connectedToView;
	private TextView lastUpdateAtView;
	private TextView messageView;

	/**
	 * Stops scan after given period
	 */
	private Runnable runnableStopScan;

	/**
	 * Restart the read
	 */
	private Runnable runnableValueReadJob;

	/**
	 * Is NULL when service is disconnected.
	 */
	private BluetoothLeService mBluetoothLeService;

	// Code to manage Service lifecycle.
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			Log.v(TAG, "onServiceConnected()");
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
			if (!mBluetoothLeService.initialize()) {
				Log.e(TAG, "Unable to initialize Bluetooth");
				finish();
			}

			//Verschoben aus onResume()
			scanLeDevice(true);

		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mBluetoothLeService = null;
		}
	};


	/**
	 * TRUE, if Device can be used for reading value data
	 */
	private boolean mGattAvailable = false;

	/**
	 * Receices GATT messages.
	 */
	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.v(TAG, "onReceive() ...");
			final String action = intent.getAction();

			// Status change to connected
			if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				Log.v(TAG, "GATT connected.");
				updateConnectionState(getString(R.string.gattConnectedTrue));
				invalidateOptionsMenu();
			}

			// Status change to disconnected
			else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
				Log.v(TAG, "GATT disconnected.");
				mGattAvailable = false;
				updateConnectionState(getString(R.string.gattConnectedFalse));
				invalidateOptionsMenu();
			}

			// Status state after Connected: Services available
			else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
				Log.v(TAG, "GATT service discovered.");
				// Show all the supported services and characteristics on the user interface.
//				Log.v(TAG, "Discovered Services: " + mBluetoothLeService.getSupportedGattServices().size());

				//It's time to start the periodically value read. Result will be received with
				// ACTION_DATA_AVAILABLE
				// Unchecked precondition: Expected RFDUINO service is available
				mGattAvailable = true;

				//It's time for our first value reading
				mBluetoothLeService.sendValueReadNotification();
			}

			// Trigger: New data available
			else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
//				Log.v(TAG, "GATT data available.");
				processMessage(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
		startJob(true);
			}

			// Default
			else {
				Log.v(TAG, "GATT unknown action " + action.toString() + ".");


			}
		}
	};
	//For timer-driven actions: Scanning end and alive-check
	private Handler mHandler;
	private BluetoothAdapter mBluetoothAdapter;
	//True while in device scanning mode
	private boolean mScanning;
	/**
	 * Will be initialized with a successful scan, otherwise NULL
	 */
	private BluetoothDevice dodDevice;
	// Device scan callback.
	private BluetoothAdapter.LeScanCallback mLeScanCallback =
			new BluetoothAdapter.LeScanCallback() {

				@Override
				public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							Log.v(TAG, "LeScanCallback.onLeScan found " + device.toString());
							//Connect to the RFDuino!
							if (device.getName().equals(Constants.Device.NAME)) {

								dodDevice = device;
								stopLeDeviceScan();
								connectedToView.setText(getString(R.string.dodConnectedTrue));
								// Automatically connects to the device upon successful start-up initialization.
								if (mBluetoothLeService != null) {
									mBluetoothLeService.connect(dodDevice.getAddress());
								} else {
									Log.v(TAG, "Missing Service, can't connect device.");
								}
							}
						}
					});
				}
			};
	private TextView gattConnectedView;
	private SharedPreferences settings;
	private Switch switchPermanent;

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
		return intentFilter;
	}

	/**
	 * When GATT is available,
	 */
	private void startJob(boolean start) {
//		Log.v(TAG, "startJob");
		if (start) {
			runnableValueReadJob = new Runnable() {
				@Override
				public void run() {
//					Log.v(TAG, "Job ended!");
					if (mBluetoothLeService != null && mGattAvailable == true) {
						mBluetoothLeService.sendValueReadNotification();
					}
				}
			};
			// Refresh every 1 Second
			mHandler.postDelayed(runnableValueReadJob, 1000);
		}
	}

	/**
	 * Analyses the value and updates storage and UI.
	 */
	private void processMessage(String data) {

		String message = null;

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

		updateUI();
	}

	/**
	 * Brings the received data to the UI.
	 */
	private void updateUI() {
		distanceValueView.setText(String.valueOf(dodDistanceValue));
		lastUpdateAtView.setText("" + sdf.format(new Date(dodLastUpdateAt)));

		String all = "";
		for (MessageBox.Msg msg : messageBox.getSorted()) {
			all += sdf.format(new Date(msg.getTimestamp())) + " " + msg.getText() + "\n";
			messageView.setText(all);
		}
	}

	/**
	 * Will be called after switching Android's BT
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// User chose not to enable Bluetooth.
		if (requestCode == Constants.Bluetooth.REQUEST_ENABLE_BT) {
			if (resultCode == Activity.RESULT_CANCELED) {
				finish();
				return;
			} else {
				scanLeDevice(true);
			}
		}


		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onStop() {
     SharedPreferences.Editor editor = settings.edit();
		editor.putString(Constants.SharedPrefs.MESSAGES,messageBox.asString());
		editor.putBoolean(Constants.SharedPrefs.SWITCH_PERMANENT, switchPermanent.isChecked());

		editor.commit();
	}

	@Override
	protected void onPause() {
		super.onPause();

		unregisterReceiver(mGattUpdateReceiver);
		scanLeDevice(false);

		//Wieder löschen, wenn onDestroy aktiviert
//		unbindService(mServiceConnection);
//		mBluetoothLeService = null;

		//Wenn hier disconnected, muss man in onResume wieder connecten !?
		if (mBluetoothLeService != null) {
			mBluetoothLeService.close();
		}

		//Store messages
		//TODO

	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		//TEstweise nach onPause verschoben
		unbindService(mServiceConnection);
		mBluetoothLeService = null;
	}

	/**
	 * Call this to start or stop the scan mode.
	 */
	private void scanLeDevice(final boolean enable) {
		Log.v(TAG, "scanLeDevice() " + enable);

		//Switching on
		if (enable) {
			runnableStopScan = new Runnable() {
				@Override
				public void run() {
					if (mScanning) {
						Log.v(TAG, "End of scan period: Stopping scan.");
						mScanning = false;
						mBluetoothAdapter.stopLeScan(mLeScanCallback);
						connectedToView.setText(getString(R.string.dodConnectedFalse));
						invalidateOptionsMenu();
					}
				}
			};
			// Stops scanning after a pre-defined scan period.
			mHandler.postDelayed(runnableStopScan, Constants.Bluetooth.SCAN_PERIOD);

			mScanning = true;
			connectedToView.setText(getString(R.string.dodConnectedScanning));
			mBluetoothAdapter.startLeScan(mLeScanCallback);


		}

		//Switching off
		else {
			stopLeDeviceScan();
		}

		invalidateOptionsMenu();
	}

	/**
	 * Removes runnableStopScan callback.
	 */
	private void stopLeDeviceScan() {
		Log.v(TAG, "stopLeDeviceScan()");
		if (mScanning) {
			mHandler.removeCallbacks(runnableStopScan);
			mScanning = false;
			mBluetoothAdapter.stopLeScan(mLeScanCallback);
			//Update status in view only if not connected.
			connectedToView.setText(getString(R.string.dodConnectedFalse));
		}
		invalidateOptionsMenu();
	}

	@Override
	protected void onResume() {
		super.onResume();

		Log.v(TAG, "onResume()");
		// Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
		// fire an intent to display a dialog asking the user to grant permission to enable it.

		//Switch on Bluetooth
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBtIntent, Constants.Bluetooth.REQUEST_ENABLE_BT);
		}

		//Broadcast receiver is our service listener
		registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

		//Mal auskommentiert, da hier erst service angebunden wird
//		if (mBluetoothLeService != null && dodDevice != null) {
//			final boolean result = mBluetoothLeService.connect(dodDevice.getAddress());
//			Log.d(TAG, "Connect request result=" + result);
//
//		}
//
//		//Start automatically scanning the device
//		if (mBluetoothAdapter.isEnabled() && dodDevice == null) {
//
//			scanLeDevice(true);
//		}

		//Verschoben von onCreate();
//		//		Log.v(TAG,"call bindService()....");
//		Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
////		Log.v(TAG, "... With result="+
//		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

		//Vielleicht geht'S ja so
		if (dodDevice == null) {
			scanLeDevice(true);
		} else {
			mBluetoothLeService.connect(dodDevice.getAddress());
		}
	}

	/*
	* Updates the connection state on the UI
	* */
	private void updateConnectionState(final String text) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				gattConnectedView.setText(text);
			}
		});
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_my);
		distanceValueView = (TextView) findViewById(R.id.distanceValue);
		connectedToView = (TextView) findViewById(R.id.connectedToValue);
		lastUpdateAtView = (TextView) findViewById(R.id.lastUpdateValue);
		messageView = (TextView) findViewById(R.id.messageValue);
		gattConnectedView = (TextView) findViewById(R.id.gattConnectedValue);
		switchPermanent = (Switch) findViewById(R.id.switchPermanent);

		//Restore values from previous run
		dodDistanceValue = getSharedPreferences(Constants.SharedPrefs.NAME, 0).getLong(Constants.SharedPrefs.DISTANCE, 0);
		dodLastUpdateAt = getSharedPreferences(Constants.SharedPrefs.NAME, 0).getLong(Constants.SharedPrefs.LAST_UPDATE_AT, 0);

		// Restore preferences
		settings = getSharedPreferences(Constants.SharedPrefs.NAME, 0);

		messageBox = new MessageBox(settings.getString(Constants.SharedPrefs.MESSAGES, null));
		switchPermanent.setChecked( settings.getBoolean(Constants.SharedPrefs.SWITCH_PERMANENT, false));

		mHandler = new Handler();

		// Use this check to determine whether BLE is supported on the device.  Then you can
		// selectively disable BLE-related features.
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			Toast.makeText(this, R.string.msgBleNotSupported, Toast.LENGTH_SHORT).show();
			finish();
		}

		// Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
		// BluetoothAdapter through BluetoothManager.
		final BluetoothManager bluetoothManager =
				(BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		mBluetoothAdapter = bluetoothManager.getAdapter();

		// Checks if Bluetooth is supported on the device.
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, R.string.msgBluetoothNotSupported, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		//Verschoben nach onResume()
//		Log.v(TAG,"call bindService()....");
		Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
//		Log.v(TAG, "... With result="+
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);


	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.my, menu);

		if (mScanning) {
			menu.findItem(R.id.menu_scan).setVisible(false);
			menu.findItem(R.id.menu_refresh).setActionView(
					R.layout.actionbar_indeterminate_progress);
		} else {
			menu.findItem(R.id.menu_scan).setVisible(true);
			menu.findItem(R.id.menu_refresh).setActionView(null);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_scan:

				mBluetoothLeService.close();
				scanLeDevice(true);
				break;

			case R.id.menu_msg1:
				messageBox.add("Meldung 1");
				updateUI();
				break;
			case R.id.menu_msg2:
				messageBox.add("Meldung 2");
				updateUI();
				break;
			case R.id.menu_msg3:
				messageBox.add("Meldung 3");
				updateUI();
				break;
			case R.id.menu_msg4:
				messageBox.add("Meldung 4");
				updateUI();
				break;
		}
		return true;
	}


	//Structure for all used Constants
	private abstract class Constants {
		abstract class SharedPrefs {
			static final String NAME = "DOD";
			static final String DISTANCE = "DISTANCE";
			static final String LAST_UPDATE_AT = "LAST_UPDATE_AT";
			static final String MESSAGES = "MESSAGES";
			static final String SWITCH_PERMANENT = "SWITCH_PERMANENT";
		}

		abstract class Device {
			static final String NAME = "DDO";
		}

		abstract class Bluetooth {
			//Max. Duration im milliseconds to scan for the ddo device
			static final long SCAN_PERIOD = 3000;

			//ID for BT switch on intent
			static final int REQUEST_ENABLE_BT = 1;
		}


	}

}
