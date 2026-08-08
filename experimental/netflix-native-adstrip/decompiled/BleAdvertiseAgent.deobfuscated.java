// Deobfuscated reconstruction of  Lo/getArguments;  (com.netflix.ninja 13.0.0-25009)
// Original class dumped into R8's generic `o` package. Log tag: "nf_bt_agent".
// This is Netflix's BLE advertiser agent used for MDX / second-screen device
// discovery — NOT video-ad logic. Reconstructed from dex bytecode; names are
// inferred from Android BLE types, log strings, and native-method signatures.
//
// Base class  Lo/getActivity;  (renamed conceptually to `NrdAgentBase`) provides:
//   Context getContext();  NetflixService getService();
//   void doInit(); void initCompleted(callback); void destroy();

package com.netflix.ninja.bluetooth;   // inferred

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.TimeUnit;

final class BleAdvertiseAgent extends NrdAgentBase {

    private static final String TAG = "nf_bt_agent";
    // <clinit>: TimeUnit.MILLISECONDS.convert(10, MINUTES)
    private static final long ADVERTISE_TIMEOUT_MS = TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES);
    public static final Companion Companion = new Companion();

    private BluetoothLeAdvertiser bleAdvertiser;                 // M135Cu0D
    private AdvertiseCallback     advertiseCallback;             // M1cMYXGO
    private AdvertiseSettings     advertiseSettings;             // M5_IQXaH
    private AdvertiseData         advertiseData;                 // MoMD214    (primary)
    private AdvertiseData         scanResponseData;              // M6H_IiaF   (optional scan-response)
    private boolean isBluetoothEnabled;                          // M1gJHszj
    private boolean isBleSupported;                              // M51RPBJe
    private boolean isLeExtendedAdvertisingSupported;            // M4mrObfZ
    private boolean isLePeriodicAdvertisingSupported;            // M5K_ewhl
    private final BroadcastReceiver bluetoothStateReceiver;      // M4znfYdB ($M135Cu0D)
    private Handler timeoutHandler;                              // M6xubM8G

    BleAdvertiseAgent(NrdAgentConfig config) {                   // <init>(Lo/getActivity$M1cMYXGO;)
        super(config);
        this.advertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(1)      // ADVERTISE_MODE_BALANCED
                .setConnectable(false)
                .setTimeout(0)
                .build();
        this.bluetoothStateReceiver = new BluetoothStateReceiver(this);   // $M135Cu0D
    }

    // ---- lifecycle (from base NrdAgentBase) ------------------------------

    @Override public void doInit() {
        Log.i(TAG, "Bluetooth::init start ");
        initBluetoothState();
        initCompleted(/* AGENT_BLUETOOTH */ Agents.BLUETOOTH);
    }

    @Override public void destroy() {
        Log.i(TAG, "Bluetooth::destroy");
        getContext().unregisterReceiver(bluetoothStateReceiver);
        super.destroy();
    }

    // MoMD214(): init() — used as a re-init + (re)start entry point
    public void init() {                                        // MoMD214()V
        initBluetoothState();
        startAdvertising();
    }

    // ---- state / capability probing -------------------------------------

    private void initBluetoothState() {                        // M1gJHszj()V
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm.getAdapter();
        this.isBluetoothEnabled = adapter != null && adapter.isEnabled();
        this.isBleSupported = getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);   // "android.hardware.bluetooth_le"
        if (isBleSupported) {
            this.bleAdvertiser = (adapter != null) ? adapter.getBluetoothLeAdvertiser() : null;
            if (Build.VERSION.SDK_INT >= 26) {
                this.isLeExtendedAdvertisingSupported =
                        adapter != null && adapter.isLeExtendedAdvertisingSupported();
                this.isLePeriodicAdvertisingSupported =
                        adapter != null && adapter.isLePeriodicAdvertisingSupported();
            }
            this.advertiseCallback = createAdvertiseCallback();
            getContext().registerReceiver(
                    bluetoothStateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED /* 4 */);
        }
        NetflixService svc = getService();
        try {
            if (svc != null) {
                svc.nativeSetBluetoothStatus(isBluetoothEnabled, isBleSupported,
                        isLeExtendedAdvertisingSupported, isLePeriodicAdvertisingSupported);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException " + e);
        }
    }

    private boolean checkPermissions() {                       // M1cMYXGO()Z
        if (AndroidUtils.isAtLeastS()) {                       // AndroidUtils.M5K_ewhl()
            if (getContext().checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                if (MainActivity.isRunning()) {                // MainActivity.M0s8NeYn()
                    Log.i(TAG, "Permission android.permission.BLUETOOTH_ADVERTISE not granted, requesting...");
                    EventBus.getDefault().post(new CommonEvent(CommonEvent.CommonEvents.REQUEST_BT_PERMISSION));
                }
                return false;
            }
        }
        if (getContext().checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission android.permission.BLUETOOTH_ADMIN not granted, cannot continue !!!");
            return false;
        }
        return true;
    }

    // ---- advertise control ----------------------------------------------

    public void startAdvertising() {                           // M135Cu0D()V
        if (!isBluetoothEnabled) {
            NetflixService svc = getService();
            if (svc != null) svc.nativeSetBluetoothAdvertiseStarted(2 /* BT_DISABLED */);
            return;
        }
        if (!isBleSupported) {
            NetflixService svc = getService();
            if (svc != null) svc.nativeSetBluetoothAdvertiseStarted(1 /* BLE_UNSUPPORTED */);
            return;
        }
        if (!checkPermissions()) return;

        Log.i(TAG, "Bluetooth::startAdvertising of data " + advertiseData);
        if (scanResponseData != null) {
            if (bleAdvertiser != null) {
                bleAdvertiser.startAdvertising(advertiseSettings, advertiseData, scanResponseData, advertiseCallback);
            }
        } else if (bleAdvertiser != null) {
            bleAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback);
        }
        // arm the 10-minute auto-stop
        this.timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutHandler.postDelayed(new AdvertiseTimeoutRunnable(this), ADVERTISE_TIMEOUT_MS);  // $getDefaultViewModelProviderFactory

        NetflixService svc = getService();
        if (svc != null) svc.nativeSetBluetoothAdvertiseStarted(4 /* STARTED */);
    }

    public void stopAdvertising() {                            // M0s8NeYn()V
        Log.i(TAG, "Bluetooth::stopAdvertising");
        if (bleAdvertiser != null) {
            bleAdvertiser.stopAdvertising(advertiseCallback);
        }
        NetflixService svc = getService();
        if (svc != null) svc.nativeSetBluetoothAdvertiseStopped();
    }

    // Called from a Handler when ADVERTISE_TIMEOUT_MS elapses.
    private static void onTimeout(BleAdvertiseAgent self) {    // M51RPBJe(Lo/getArguments;)V
        Log.i(TAG, "AdvertiserService has reached timeout of " + ADVERTISE_TIMEOUT_MS + " milliseconds, stopping advertising.");
        self.stopAdvertising();
    }

    // type: 0 = scan-response slot, 1 = advertise slot
    public void clearAdvertiseData(int type) {                 // M0s8NeYn(I)V
        if (type == 0)       this.scanResponseData = null;
        else if (type == 1)  this.advertiseData    = null;
    }

    public boolean setAdvertisingSettings(int mode, boolean connectable, int timeoutMs) {  // N(I Z I)Z
        Log.i(TAG, "Bluetooth::setAdvertisingSettings");
        try {
            this.advertiseSettings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(mode)
                    .setConnectable(connectable)
                    .setTimeout(timeoutMs)
                    .build();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth::setAdvertisingSettings FAILED with exception " + e.getMessage());
            return false;
        }
    }

    // Builds an AdvertiseData; `dataType` (last-ish arg) routes to advertise vs scan-response slot.
    public boolean addAdvertiseData(int dataType, boolean includeDeviceName, String serviceUuid,
                                    boolean includeTxPower, String serviceDataUuid, boolean hasSolicitationUuid,
                                    String solicitationUuid, String serviceData, boolean hasServiceData,
                                    int manufacturerId, String manufacturerData, boolean hasManufacturerData,
                                    boolean includeTxPowerLevel) {          // N(I Z String Z String Z String String Z I String Z Z)Z
        Log.i(TAG, "Bluetooth::addAdvertiseData");
        try {
            AdvertiseData.Builder b = new AdvertiseData.Builder()
                    .setIncludeDeviceName(includeDeviceName)
                    .setIncludeTxPowerLevel(includeTxPowerLevel);
            if (includeTxPower && serviceUuid != null && serviceUuid.length() != 0) {
                b.addServiceUuid(ParcelUuid.fromString(serviceUuid));
            }
            if (hasServiceData && serviceData != null && serviceData.length() != 0
                    && serviceDataUuid != null && serviceDataUuid.length() != 0) {
                b.addServiceData(ParcelUuid.fromString(serviceDataUuid),
                                 serviceData.getBytes(/* UTF-8 */));
            }
            if (hasManufacturerData) {
                byte[] mfg = (manufacturerData != null) ? manufacturerData.getBytes() : null;
                b.addManufacturerData(manufacturerId, mfg);
            }
            if (Build.VERSION.SDK_INT >= 31 && hasSolicitationUuid
                    && solicitationUuid != null && solicitationUuid.length() != 0) {
                b.addServiceSolicitationUuid(ParcelUuid.fromString(solicitationUuid));
            }
            if (dataType == 0)      this.scanResponseData = b.build();
            else if (dataType == 1) this.advertiseData    = b.build();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth::addAdvertiseData FAILED with exception " + e.getMessage());
            return false;
        }
    }

    private AdvertiseCallback createAdvertiseCallback() {       // N()Lo/getArguments$MoMD214;
        return new BleAdvertiseCallback(this);                 // $MoMD214
    }
}
