package de.egh.ddoapp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

import de.egh.dynamodrivenodometer.AppService;
import de.egh.dynamodrivenodometer.MessageBox;


public class MainActivity extends AppCompatActivity {

    /**
     * Broadcasts have no order.
     */
    private long lastBroadcast = 0;

    // This deviceName must be set in the RFDuino
    private static final String TAG = MainActivity.class.getSimpleName();
    /**
     * Receices GATT messages.
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //		Log.v(TAG, "onReceive() ...");
            final String action = intent.getAction();
            long timestamp = intent.getLongExtra(AppService.Constants.EXTRA_TIMESTAMP, 0);
            boolean isDemo = intent.getBooleanExtra(AppService.Constants.EXTRA_IS_DEMO, false);
            String deviceName = intent.getStringExtra(AppService.Constants.EXTRA_DEVICE_NAME);

            //Ignore old messages. That's not really fine...
            if (timestamp < lastBroadcast) {
                Log.v(TAG, "Received broadcast is to old, ignoring: " + timestamp + "/" + deviceName + "/" + action + "/" + isDemo);
                return;
            }


            //Ignore event for previous device
            switch (mActualDevice.getScreenNr()) {
                case Constants.LIVE_SCREEN_NR:
                    if (isDemo) {
                        Log.v(TAG, "Received broadcast for wrong screen, ignoring: " + timestamp + "/" + deviceName + "/" + action + "/" + isDemo);
                        return;
                    }
                    break;
                case Constants.DEMO_SCREEN_NR:
                    if (!isDemo) {
                        Log.v(TAG, "Received broadcast for wrong screen, ignoring: " + timestamp + "/" + deviceName + "/" + action + "/" + isDemo);
                        return;
                    }
                    break;
                default:
                    Log.e(TAG, "Unexpected screen number=" + mActualDevice.getScreenNr());
                    return;
            }

            Log.v(TAG, "Received broadcast: " + timestamp + "/" + deviceName + "/" + action + "/" + isDemo);

            // Status change to connected
            switch (action) {


                // Status change to disconnected
                case AppService.Constants.Actions.NOT_CONNECTED:
                    Log.v(TAG, "Received: not connected.");
                    mActualDevice.setAvailable(false);
                    mActualDevice.setScanmode(Scanmode.OFF);
                    updateUI();
                    break;

                // Status state after Connected: Services available
                case AppService.Constants.Actions.CONNECTED:
                    Log.v(TAG, "Received: connected.");
                    mActualDevice.setAvailable(true);
                    mActualDevice.setScanmode(Scanmode.OFF);
                    mActualDevice.setDeviceName(deviceName);
                    //It's time for our first value reading
                    mService.readValue();
                    updateUI();
                    break;

                // Trigger: New data available
                case AppService.Constants.Actions.DATA_AVAILABLE:
//				Log.v(TAG, "GATT data available.");
                    processMessage(intent.getStringExtra(AppService.Constants.EXTRA_DATA));
                    startJob();
                    break;

                case AppService.Constants.Actions.SCANNING:
                    Log.v(TAG, "Received: Scanning");
                    mActualDevice.setScanmode(Scanmode.SCANNING);
                    updateUI();
                    break;
                case AppService.Constants.Actions.DEVICE:
                    //TODO Braucht man den?
                    Log.v(TAG, "Received: DEVICE. Who needs this Broadcast?");
                    break;

                // Default
                default:
                    Log.v(TAG, "Received unknown action: " + action + ".");
                    break;
            }
        }
    };
    // Shown device
    DeviceScreen mActualDevice;
    //Messages from the device
    private MessageBox messageBox;
    private SimpleDateFormat mSdf = new SimpleDateFormat("dd.MM HH:mm:ss");
    private TextView distanceValueView;
    private TextView connectedToView;
    private TextView lastUpdateAtView;
    private TextView messageView;
    /**
     * Restart the read
     */
    private Runnable mRunnableValueReadJob;
    /**
     * Is NULL when service is disconnected.
     */
    private AppService mService;
    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.v(TAG, "onServiceConnected()");

            mService = ((AppService.LocalBinder) service).getService();
            // We want service to alive after unbinding to hold the actual status
//			startService(new Intent(MainActivity.this, AppService.class));

            mService.broadcastStatus();

            startScanning();

        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mService = null;
            Log.v(TAG, "onServiceDisconnected()");
        }
    };
    /**
     * TRUE, if Device can be used for reading value data
     */
    //For timer-driven actions: Connection end and alive-check
    private Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private TextView distanceMeterValueView;
    private TextView mDeviceNameView;
    private LinearLayout connectButton;

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AppService.Constants.Actions.NOT_CONNECTED);
        intentFilter.addAction(AppService.Constants.Actions.CONNECTED);
        intentFilter.addAction(AppService.Constants.Actions.DATA_AVAILABLE);
        intentFilter.addAction(AppService.Constants.Actions.SCANNING);

        return intentFilter;
    }

    /**
     * When GATT is available,
     */
    private void startJob() {
//		Log.v(TAG, "startJob");

        //Initialize job one time.
        mRunnableValueReadJob = new Runnable() {

            @Override
            public void run() {
//					Log.v(TAG, "Job ended!");
                if (mService != null && mActualDevice.isAvailable()) {
                    mService.readValue();
                }
            }
        };
        // Refresh every 1 Second
        mHandler.postDelayed(mRunnableValueReadJob, 1000);
    }

    /**
     * Analyses the value and updates storage and UI.
     */
    private void processMessage(String data) {

        String message = null;
        Log.v(TAG, "processMessage() " + data);

        if (data != null && data.length() > 0) {
//			Log.v(TAG, "Value >>>" + data + "<<<");

            //First character holds the type.
            char dataType = data.charAt(0);
            String dataContent = data.substring(1);

            //Distance value: Distance in Millimeters
            if (dataType == AppService.Constants.Datatypes.DISTANCE.charAt(0)) {

                long value;

                //Expecting the distance in mm as long value
                try {
                    //Translate to Meters
                    value = Long.valueOf(dataContent) / 1000;
                    mActualDevice.setDistance(value);
                    mActualDevice.setLastUpdateAt(System.currentTimeMillis());
                    Log.v(TAG, "Value in m=" + mActualDevice.getDistance());

                } catch (Exception e) {
                    Log.v(TAG, "Caught exception: Not a long value >>>" + dataContent + "<<<");
                    message = "Not a number >>>" + dataContent + "<<<";
                }
            }
            // Message
            else if (dataType == AppService.Constants.Datatypes.MESSAGE.charAt(0)) {
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
//			messageBox.add(message); TODO
        }

        updateUI();
    }

    /**
     * Call this every time a UI value has changed. Also updates the menu.
     */

    private void updateUI() {

        invalidateOptionsMenu();

        mDeviceNameView.setText(mActualDevice.getDeviceName());

        if (mActualDevice.isAvailable()) {
            connectedToView.setText(getString(R.string.dodConnectedTrue));
            connectButton.setBackgroundColor(getResources().getColor(R.color.connectionStatusConnected));
        } else {
            // Status
            if (mActualDevice.getScanmode().equals(Scanmode.SCANNING)) {
                connectedToView.setText(getString(R.string.dodConnectedScanning));
                connectButton.setBackgroundColor(getResources().getColor(R.color.connectionStatusConnecting));
            } else {
                connectedToView.setText(getString(R.string.dodConnectedFalse));
                connectButton.setBackgroundColor(getResources().getColor(R.color.connectionStatusDisconnected));
            }
        }

        // Values
        distanceValueView.setText(String.format("%,d", mActualDevice.getDistance() / 1000));
        distanceMeterValueView.setText(String.format("%03d", mActualDevice.getDistance() % 1000));
        if (mActualDevice.getLastUpdateAt() > 0) {
            lastUpdateAtView.setText(mSdf.format(new Date(mActualDevice.getLastUpdateAt())));
        } else {
            lastUpdateAtView.setText(R.string.lastUpdateDefault);
        }

//		messageView.setText(messageBox.asString());

    }

    /**
     * Will be called after switching Android's BT. Don't start scanning here, because onResume() will
     * be called after later.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.v(TAG, "onActivityResult()");
        // User chose not to enable Bluetooth.
        if (requestCode == Constants.Bluetooth.REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, R.string.messageBluetoothNotOn, Toast.LENGTH_SHORT).show();
                mActualDevice.setScanmode(Scanmode.OFF);
                updateUI();
            } else {
                mActualDevice.setScanmode(Scanmode.SWITCH_ON);
                startScanning();
                updateUI();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onStop()");

        //Save actual values
        mActualDevice.save();

        // Save actual Device view
        SharedPreferences.Editor editor = getSharedPreferences(Constants.SharedPrefs.PREFS_NAME_APP, 0).edit();
        editor.putInt(Constants.SharedPrefs.ACTUAL_DEVICE_SCREEN_NR, mActualDevice.getScreenNr());
        editor.apply();

        if (mRunnableValueReadJob != null) {
            mHandler.removeCallbacks(mRunnableValueReadJob);
        }

        if (mService != null) {
            mService.closeDeviceConnection();
        }

        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //TEstweise nach onPause verschoben
        unbindService(mServiceConnection);
        mService = null;
    }


    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart()");

        //Restore values from previous run. Take live view, if first app start.
        mActualDevice = DeviceScreen.createLastDevice(
                getSharedPreferences(Constants.SharedPrefs.PREFS_NAME_APP, 0)
                        .getInt(Constants.SharedPrefs.ACTUAL_DEVICE_SCREEN_NR, Constants.LIVE_SCREEN_NR), this);


        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.

        //Broadcast receiver is our service listener
        registerReceiver(mBroadcastReceiver, makeGattUpdateIntentFilter());

        if (mService != null) {
            startScanning();
        }
        updateUI();
    }

    /**
     * Call this to switch to scanning mode. UI will not be updated. App will be finished, if BT could
     * not be established.
     */
    private void startScanning() {
        Log.v(TAG, "startScanning()");

        if (mActualDevice.getScreenNr() == Constants.LIVE_SCREEN_NR && !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, Constants.Bluetooth.REQUEST_ENABLE_BT);
        } else if (mService != null) {
            if (!mService.scanDevice(mActualDevice.getScreenNr() == Constants.DEMO_SCREEN_NR)) {
                Log.e(TAG, "Unable to start Bluetooth");
                finish();
            }
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "coarse location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
            }
        }
    }

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        distanceValueView = (TextView) findViewById(R.id.distanceValue);
        distanceMeterValueView = (TextView) findViewById(R.id.distanceMeterValue);
        connectedToView = (TextView) findViewById(R.id.connectedToValue);
        connectButton = (LinearLayout) findViewById(R.id.connectButton);
        lastUpdateAtView = (TextView) findViewById(R.id.lastUpdateValue);
        messageView = (TextView) findViewById(R.id.messageValue);
        mDeviceNameView = (TextView) findViewById(R.id.deviceNameValue);


        mHandler = new Handler();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            // Android M Permission check

            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can detect beacons.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }

        }

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
            return;
        }

        //Verschoben nach onResume()
//		Log.v(TAG,"call bindService()....");
        Intent gattServiceIntent = new Intent(this, AppService.class);

        //		Log.v(TAG, "... With result="+
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);


    }

    /**
     * Event for big colored button.
     */
    public void onClickConnectButton(View view) {
        Log.v(TAG, "onClickConnectButton");
        mService.closeDeviceConnection();
        startScanning();
        updateUI();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (mActualDevice.getScreenNr() == Constants.DEMO_SCREEN_NR) {
            menu.findItem(R.id.menu_demo).setTitle(R.string.menuDemoActive);
        } else {
            menu.findItem(R.id.menu_demo).setTitle(R.string.menuDemo);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_demo:

                //Save data for leaving screen
                mActualDevice.save();

                mHandler.removeCallbacks(mRunnableValueReadJob);
                if (mActualDevice.getScreenNr() == Constants.DEMO_SCREEN_NR) {
                    //Create Screen for live device
                    mActualDevice = DeviceScreen.createLastDevice(Constants.LIVE_SCREEN_NR, this);
                } else {
                    //Switch to demo device
                    mActualDevice = DeviceScreen.createLastDevice(Constants.DEMO_SCREEN_NR, this);
                }
                startScanning();
                updateUI();
                break;
        }

        return true;
    }

    /**
     * There are four steps for scanning devices.
     */
    private enum Scanmode {
        /**
         * While preparing, BT will be switched on
         */
        PREPARING_BT(1),

        /**
         * Scan mode switched on,but not started
         */
        SWITCH_ON(2),

        /**
         * Service is scanning
         */
        SCANNING(3),

        /**
         * Scanning not active
         */
        OFF(0);

        private int mode;

        Scanmode(int mode) {
            this.mode = mode;
        }

        public static Scanmode create(int mode) {
            switch (mode) {
                case 1:
                    return Scanmode.PREPARING_BT;
                case 2:
                    return Scanmode.SWITCH_ON;
                case 3:
                    return Scanmode.SCANNING;
                default:
                    return Scanmode.OFF;
            }
        }

        public int getValue() {
            return mode;
        }

    }

    /**
     * Functional class for a device screen shown in the activity. The view is identified by an ID.
     * The device can be unknown at starting time, for instance while scanning. But the page should
     * always show the last conected device. The SharedPreferences is defined by the ID.
     */
    private static class DeviceScreen {

        private String deviceName;
        private long distance;
        private long lastUpdateAt;
        private Scanmode scanmode;
        private boolean available;
        private Context context;
        private int screenNr;

        /**
         * Create new device an initialite with persisted values, if not the null object. Context is
         * needed for persisting. If deviceName is <code>Constants.DEMO_DEVICE_NAME</code>, than the
         * Device will created as demo device.
         */
        private DeviceScreen(int screnNr, Context context, String deviceName) {
            this.screenNr = screnNr;
            this.context = context;
            this.deviceName = deviceName;
            load();
        }

        /**
         * Create view with last device. Use this to create an view before/while scanning.
         */
        public static DeviceScreen createLastDevice(int screenNr, Context context) {
            return new DeviceScreen(screenNr, context, null);
        }

        public int getScreenNr() {
            return screenNr;
        }

        public String getDeviceName() {
            return deviceName;
        }

        /**
         * Save object to persisting infrastructure.
         */
        public void save() {

            SharedPreferences.Editor editor = getSharedPrefs().edit();
            editor.putLong(Constants.SharedPrefs.DISTANCE, distance);
            editor.putLong(Constants.SharedPrefs.LAST_UPDATE_AT, lastUpdateAt);
            editor.putInt(Constants.SharedPrefs.SCANNING, scanmode.getValue());
            editor.putString(Constants.SharedPrefs.DEVICE_NAME, deviceName);
            editor.apply();
        }

        /**
         * Returns the SharedPrefernces for the persisting data.
         */
        private SharedPreferences getSharedPrefs() {
            return context.getSharedPreferences(Constants.SharedPrefs.PREFS_NAME_PREFIX_DEVICE_SCREEN + String.valueOf(screenNr), 0);
        }

        public void setDeviceName(String deviceName) {
            this.deviceName = deviceName;
        }

        public long getLastUpdateAt() {
            return lastUpdateAt;
        }

        public void setLastUpdateAt(long lastUpdateAt) {
            this.lastUpdateAt = lastUpdateAt;
        }

        public long getDistance() {
            return distance;
        }

        public void setDistance(long distance) {
            this.distance = distance;
        }

        public Scanmode getScanmode() {
            return scanmode;
        }

        public void setScanmode(Scanmode scanmode) {
            this.scanmode = scanmode;
        }

        public boolean isAvailable() {
            return available;
        }

        public void setAvailable(boolean available) {
            this.available = available;
        }

        /**
         * Load the device from memory. Only internal usage.
         */
        private void load() {
            setDeviceName(getSharedPrefs().getString(Constants.SharedPrefs.DEVICE_NAME, Constants.UNKNOWN_DEVICE_NAME));
            setDistance(getSharedPrefs().getLong(Constants.SharedPrefs.DISTANCE, 0));
            setAvailable(getSharedPrefs().getBoolean(Constants.SharedPrefs.AVAILABLE, false));
            setLastUpdateAt(getSharedPrefs().getLong(Constants.SharedPrefs.LAST_UPDATE_AT, 0));
            setScanmode(Scanmode.create(getSharedPrefs().getInt(Constants.SharedPrefs.SCANNING, 1)));
        }
    }

    //Structure for all used Constants
    private abstract class Constants {
        static final String UNKNOWN_DEVICE_NAME = "---";
        static final int DEMO_SCREEN_NR = 1;
        static final int LIVE_SCREEN_NR = 0;

        abstract class SharedPrefs {
            static final String ACTUAL_DEVICE_SCREEN_NR = "ACTUAL_DEVICE_SCREEN_NR";
            static final String DEVICE_NAME = "DEVICE_NAME";
            static final String PREFS_NAME_PREFIX_DEVICE_SCREEN = "DEVICE_SCREEN_PREFERENCES_";
            static final String PREFS_NAME_APP = "APP_PREFERENCES";
            static final String DISTANCE = "DISTANCE";
            static final String LAST_UPDATE_AT = "LAST_UPDATE_AT";
            static final String SCANNING = "SCANNING";
            static final String AVAILABLE = "AVAILABLE";
        }

        abstract class Bluetooth {

            //ID for BT switch on intent
            static final int REQUEST_ENABLE_BT = 1;
        }


    }
}
