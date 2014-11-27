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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

import de.egh.dynamodrivenodometer.Exceptions.EghBluetoothLeNotSupported;
import de.egh.dynamodrivenodometer.Exceptions.EghBluetoothNotSupported;

import static de.egh.dynamodrivenodometer.DeviceType.DDO;
import static de.egh.dynamodrivenodometer.R.id.menuMockbike;


public class MyActivity extends Activity {

	// This name must be set in the RFDuino
	private static final String TAG = MyActivity.class.getSimpleName();
	private static final SimpleDateFormat mSdf = new SimpleDateFormat("dd.MM HH:mm:ss");
	//Last time a distance value has been received as timestamp
	private long mDodLastUpdateAt;
	//Distance in meters
	private long mDistance;
	//Messages from the device
	private MessageBox mMessageBox;
	private TextView mConnectedToView;
	private TextView mCastUpdateAtView;
	private TextView mMessageView;
	private TextView mGattConnectedView;
	private SharedPreferences mSettings;
	private Switch mConnectSwitch;
	//For timer-driven actions: Scanning end and alive-check
	private Handler mHandler;
	private BluetoothAdapter mBluetoothAdapter;
	//True while in device scanning mode
	private boolean mScanning;
	/**
	 * Will be initialized with a successful scan, otherwise NULL
	 */
	private BluetoothDevice mDodDevice;


	/**
	 * Stops scan after given period
	 */
	private Runnable mRunnableStopScan;

	/**
	 * Restart the read
	 */
	private Runnable mRunnableValueReadJob;

	/**
	 * Is NULL when service is disconnected.
	 */
	private DeviceService mService;

	// Code to manage Service lifecycle.
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			Log.v(TAG, "onServiceConnected()");
			mService = ((DeviceService.LocalBinder) service).getService();
			if (!mService.initialize()) {
				Log.e(TAG, "Unable to initialize Bluetooth");
				finish();
			}

			//Verschoben aus onResume()
			scanLeDevice(true);

			updateUI();

		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mService = null;
		}
	};
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
							if (device.getName().equals(MyActivity.Constants.Device.NAME)) {

								mDodDevice = device;
								stopLeDeviceScan();
								mConnectedToView.setText(getString(R.string.dodConnectedTrue));
								// Automatically connects to the device upon successful start-up initialization.
								if (mService != null) {
									mService.connect(mDodDevice.getAddress());
								} else {
									Log.v(TAG, "Missing Service, can't connect device.");
								}
							}
						}
					});
				}
			};
	/**
	 * TRUE, if Device can be used for reading value data
	 */
	private boolean mGattAvailable = false;
	/**
	 * Receices GATT messages.
	 */
	private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.v(TAG, "onReceive() ...");
			final String action = intent.getAction();

			// Status change to connected
			if (DeviceService.Constants.Actions.ACTION_GATT_CONNECTED.equals(action)) {
				Log.v(TAG, "GATT connected.");
				updateConnectionState(getString(R.string.gattConnectedTrue));
				invalidateOptionsMenu();
			}

			// Status change to disconnected
			else if (DeviceService.Constants.Actions.ACTION_GATT_DISCONNECTED.equals(action)) {
				Log.v(TAG, "GATT disconnected.");
				mGattAvailable = false;
				updateConnectionState(getString(R.string.gattConnectedFalse));
				invalidateOptionsMenu();
			}

			// Status state after Connected: Services available
			else if (DeviceService.Constants.Actions.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
				Log.v(TAG, "GATT service discovered.");
				// Show all the supported services and characteristics on the user interface.
//				Log.v(TAG, "Discovered Services: " + mService.getSupportedGattServices().size());

				//It's time to start the periodically value read. Result will be received with
				// ACTION_DATA_AVAILABLE
				// Unchecked precondition: Expected RFDUINO service is available
				mGattAvailable = true;

				//It's time for our first value reading
				mService.sendValueReadNotification();
			}

			// Trigger: New data available
			else if (DeviceService.Constants.Actions.ACTION_VALUE_AVAILABLE.equals(action)) {
//				Log.v(TAG, "GATT data available.");
				processMessage(intent.getStringExtra(DeviceService.Constants.Broadcast.DATA));
				startJob(true);
			}

			// Activity has to switch BT on
		else if(DeviceService.Constants.Actions.ACTION_BLUETOOTH_NEEDED.equals(action)){
				//Switch on Bluetooth
				if ((((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter()).isEnabled()) {
					Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
					startActivityForResult(enableBtIntent, MyActivity.Constants.Bluetooth.REQUEST_ENABLE_BT);
				}
			}

			// Default should never reached
			else {
				Log.v(TAG, "Unknown broadcast action " + action.toString() + ".");


			}
		}
	};
	private TextView mDistanceValueView;

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(DeviceService.Constants.Actions.ACTION_GATT_CONNECTED);
		intentFilter.addAction(DeviceService.Constants.Actions.ACTION_GATT_DISCONNECTED);
		intentFilter.addAction(DeviceService.Constants.Actions.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(DeviceService.Constants.Actions.ACTION_VALUE_AVAILABLE);
		return intentFilter;
	}

	/**
	 * When GATT is available,
	 */
	private void startJob(boolean start) {
//		Log.v(TAG, "startJob");
		if (start) {
			mRunnableValueReadJob = new Runnable() {
				@Override
				public void run() {
//					Log.v(TAG, "Job ended!");
					if (mService != null && mGattAvailable == true) {
						mService.sendValueReadNotification();
					}
				}
			};
			// Refresh every 1 Second
			mHandler.postDelayed(mRunnableValueReadJob, 1000);
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
					mDistance = value;
					mDodLastUpdateAt = System.currentTimeMillis();
//					Log.v(TAG, "Value=" + mDistance);

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
			mMessageBox.add(message);
		}

		updateUI();
	}

	/**
	 * Brings the received data to the UI.
	 */
	private void updateUI() {

		if (mService != null) {
			switch (mService.getDeviceType()) {
				case DDO:
					setTitle(R.string.deviceTypeDDO);
					break;
				case MOCKBIKE:
					setTitle(R.string.deviceTypeMockbike);
					break;
				default:
					setTitle(R.string.app_name);
			}
		} else {
			setTitle(R.string.app_name);
		}


		mDistanceValueView.setText(String.valueOf(mDistance));
		mCastUpdateAtView.setText("" + mSdf.format(new Date(mDodLastUpdateAt)));

		String all = "";
		for (MessageBox.Msg msg : mMessageBox.getSorted()) {
			all += mSdf.format(new Date(msg.getTimestamp())) + " " + msg.getText() + "\n";
			mMessageView.setText(all);
		}
	}

	/**
	 * Will be called after switching Android's BT
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// User chose not to enable Bluetooth.
		if (requestCode == MyActivity.Constants.Bluetooth.REQUEST_ENABLE_BT) {
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
		SharedPreferences.Editor editor = mSettings.edit();
		editor.putString(MyActivity.Constants.SharedPrefs.MESSAGES, mMessageBox.asString());
		editor.putBoolean(MyActivity.Constants.SharedPrefs.SWITCH_PERMANENT, mConnectSwitch.isChecked());

		editor.commit();

		super.onStop();
	}

	@Override
	protected void onPause() {
		super.onPause();

		unregisterReceiver(mBroadcastReceiver);
		scanLeDevice(false);

		//Wieder löschen, wenn onDestroy aktiviert
//		unbindService(mServiceConnection);
//		mService = null;

		//Wenn hier disconnected, muss man in onResume wieder connecten !?
		if (mService != null) {
			mService.close();
		}

		//Store messages
		//TODO

	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		//TEstweise nach onPause verschoben
		unbindService(mServiceConnection);
		mService = null;
	}

	/**
	 * Call this to start or stop the scan mode.
	 */
	private void scanLeDevice(final boolean enable) {
		Log.v(TAG, "scanLeDevice() " + enable);

		//Switching on
		if (enable) {
			if (mService != null) {
				mService.close();
			}
			mRunnableStopScan = new Runnable() {
				@Override
				public void run() {
					if (mScanning) {
						Log.v(TAG, "End of scan period: Stopping scan.");
						mScanning = false;
						mBluetoothAdapter.stopLeScan(mLeScanCallback);
						mConnectedToView.setText(getString(R.string.dodConnectedFalse));
						mConnectSwitch.setChecked(false);
					}
				}
			};
			// Stops scanning after a pre-defined scan period.
			mHandler.postDelayed(mRunnableStopScan, MyActivity.Constants.Bluetooth.SCAN_PERIOD);

			mScanning = true;
			mConnectedToView.setText(getString(R.string.dodConnectedScanning));
			mBluetoothAdapter.startLeScan(mLeScanCallback);


		}

		//Switching off
		else {
			stopLeDeviceScan();
		}
	}

	/**
	 * Removes mRunnableStopScan callback.
	 */
	private void stopLeDeviceScan() {
		Log.v(TAG, "stopLeDeviceScan()");
		if (mScanning) {
			mHandler.removeCallbacks(mRunnableStopScan);
			mScanning = false;
			mBluetoothAdapter.stopLeScan(mLeScanCallback);
			//Update status in view only if not connected.
			mConnectedToView.setText(getString(R.string.dodConnectedFalse));
		}
		invalidateOptionsMenu();
	}

	@Override
	protected void onResume() {
		super.onResume();

		Log.v(TAG, "onResume()");
		// Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
		// fire an intent to display a dialog asking the user to grant permission to enable it.

//		//Switch on Bluetooth
//		if (!mBluetoothAdapter.isEnabled()) {
//			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//			startActivityForResult(enableBtIntent, MyActivity.Constants.Bluetooth.REQUEST_ENABLE_BT);
//		}

		//Broadcast receiver is our service listener
		registerReceiver(mBroadcastReceiver, makeGattUpdateIntentFilter());

		//Mal auskommentiert, da hier erst service angebunden wird
//		if (mService != null && mDodDevice != null) {
//			final boolean result = mService.connect(mDodDevice.getAddress());
//			Log.d(TAG, "Connect request result=" + result);
//
//		}
//
//		//Start automatically scanning the device
//		if (mBluetoothAdapter.isEnabled() && mDodDevice == null) {
//
//			scanLeDevice(true);
//		}

		//Verschoben von onCreate();
//		//		Log.v(TAG,"call bindService()....");
//		Intent gattServiceIntent = new Intent(this, DeviceService.class);
////		Log.v(TAG, "... With result="+
//		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

		//Vielleicht geht'S ja so
		if (mDodDevice == null) {
			scanLeDevice(true);
		} else {
			mService.connect(mDodDevice.getAddress());
		}
	}

	/*
	* Updates the connection state on the UI
	* */
	private void updateConnectionState(final String text) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mGattConnectedView.setText(text);
			}
		});
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_my);
		mDistanceValueView = (TextView) findViewById(R.id.distanceValue);
		mConnectedToView = (TextView) findViewById(R.id.connectedToValue);
		mCastUpdateAtView = (TextView) findViewById(R.id.lastUpdateValue);
		mMessageView = (TextView) findViewById(R.id.messageValue);
		mGattConnectedView = (TextView) findViewById(R.id.gattConnectedValue);
		mConnectSwitch = (Switch) findViewById(R.id.connectSwitch);

		//Restore values from previous run
		mDistance = getSharedPreferences(MyActivity.Constants.SharedPrefs.NAME, 0).getLong(MyActivity.Constants.SharedPrefs.DISTANCE, 0);
		mDodLastUpdateAt = getSharedPreferences(MyActivity.Constants.SharedPrefs.NAME, 0).getLong(MyActivity.Constants.SharedPrefs.LAST_UPDATE_AT, 0);

		// Restore preferences
		mSettings = getSharedPreferences(MyActivity.Constants.SharedPrefs.NAME, 0);

		mMessageBox = new MessageBox(mSettings.getString(MyActivity.Constants.SharedPrefs.MESSAGES, null));
		mConnectSwitch.setChecked(mSettings.getBoolean(MyActivity.Constants.SharedPrefs.SWITCH_PERMANENT, false));

		mHandler = new Handler();

//		// Use this check to determine whether BLE is supported on the device.  Then you can
//		// selectively disable BLE-related features.
//		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
//			Toast.makeText(this, R.string.msgBleNotSupported, Toast.LENGTH_SHORT).show();
//			finish();
//		}

//		// Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
//		// BluetoothAdapter through BluetoothManager.
//		final BluetoothManager bluetoothManager =
//				(BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
//		mBluetoothAdapter = bluetoothManager.getAdapter();
//
//		// Checks if Bluetooth is supported on the device.
//		if (mBluetoothAdapter == null) {
//			Toast.makeText(this, R.string.msgBluetoothNotSupported, Toast.LENGTH_SHORT).show();
//			finish();
//			return;
//		}

		//Verschoben nach onResume()
//		Log.v(TAG,"call bindService()....");
		Intent gattServiceIntent = new Intent(this, DeviceService.class);
//		Log.v(TAG, "... With result="+
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);


	}

	/**
	 * Callback method for connect switch
	 */
	public void onClickConnectSwitch(View view) {
		if (mConnectSwitch.isChecked()) {
			Log.v(TAG, "ConnectSwitch checked");



			//BT is enabled. We can start the device
			else{


			}
	//		scanLeDevice(true);

		} else {
			Log.v(TAG, "ConnectSwitch unchecked");
		//	scanLeDevice(false);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.my, menu);
/*
		if (mScanning) {
			menu.findItem(R.id.menu_scan).setVisible(false);
			//		menu.findItem(R.id.menu_refresh).setActionView(
			//			R.layout.actionbar_indeterminate_progress);
		} else {
			menu.findItem(R.id.menu_scan).setVisible(true);
			//		menu.findItem(R.id.menu_refresh).setActionView(null);
		}
	*/
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		try {
			switch (item.getItemId()) {
				case R.id.menuBromptonBike:
					mService.changeDevice(DDO, this);
					break;
				case menuMockbike:
					mService.changeDevice(DeviceType.MOCKBIKE, this);
					break;

			}
		} catch (EghBluetoothNotSupported eghBluetoothNotSupported) {
			Toast.makeText(this, R.string.msgBluetoothNotSupported, Toast.LENGTH_SHORT).show();
		} catch (EghBluetoothLeNotSupported eghBluetoothLeNotSupported) {
			Toast.makeText(this, R.string.msgBleNotSupported, Toast.LENGTH_SHORT).show();
		}
					updateUI();
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
			static final long SCAN_PERIOD = 10000;

			//ID for BT switch on intent
			static final int REQUEST_ENABLE_BT = 1;
		}


	}

}
