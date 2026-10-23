/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import static android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.SearchManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.fr3ts0n.androbd.plugin.Plugin;
import com.fr3ts0n.androbd.plugin.mgr.PluginManager;
import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataItems;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.ProcessVar;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvChangeListener;
import com.fr3ts0n.pvs.PvList;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Main Activity for AndrOBD app
 */
public class MainActivity extends PluginManager
        implements PvChangeListener,
        AdapterView.OnItemLongClickListener,
        PropertyChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        AbsListView.MultiChoiceModeListener
{
    /**
     * Key names for preferences
     */
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";
    public static final String PREF_AUTOHIDE = "autohide_toolbar";
    public static final String PREF_FULLSCREEN = "full_screen";
    public static final String PREF_AUTOHIDE_DELAY = "autohide_delay";

    /**
     * Message types sent from the communication service Handler
     */
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_FILE_READ = 2;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    public static final int MESSAGE_UPDATE_VIEW = 7;
    public static final int MESSAGE_TOOLBAR_VISIBLE = 12;

    /**
     * Mapping list from Plugin.CsvField to EcuDataPv.key
     */
    static final Object[] csvFidMap =
    {
        EcuDataPv.FID_MNEMONIC,
        EcuDataPv.FIELDS[EcuDataPv.FID_DESCRIPT],
        EcuDataPv.FID_MIN,
        EcuDataPv.FID_MAX,
        EcuDataPv.FIELDS[EcuDataPv.FID_UNITS]
    };

    private static final String DEVICE_ADDRESS = "device_address";
    private static final String DEVICE_PORT = "device_port";
    private static final String DEFAULT_WIFI_ADDRESS = "192.168.0.10";
    private static final int DEFAULT_WIFI_PORT = 35000;
    private static final String MEASURE_SYSTEM = "measure_system";
    /** legacy boolean preference key, kept only to migrate existing users' choice */
    private static final String NIGHT_MODE = "night_mode";
    /** tri-state theme preference key: "system", "light" or "dark" */
    private static final String THEME_MODE = "theme_mode";
    private static final String ELM_ADAPTIVE_TIMING = "adaptive_timing_mode";
    private static final String ELM_RESET_ON_NRC = "elm_reset_on_nrc";
    private static final String PREF_USE_LAST = "USE_LAST_SETTINGS";
    private static final String PREF_OVERLAY = "toolbar_overlay";
    private static final String PREF_DATA_DISABLE_MAX = "data_disable_max";

    private static final int MESSAGE_FILE_WRITTEN = 3;
    protected static final int MESSAGE_DATA_ITEMS_CHANGED = 6;
    private static final int MESSAGE_OBD_STATE_CHANGED = 8;
    private static final int MESSAGE_OBD_NUMCODES = 9;
    private static final int MESSAGE_OBD_ECUS = 10;
    private static final int MESSAGE_OBD_NRC = 11;
    private static final String TAG = "AndrOBD";

    /**
     * internal Intent request codes
     */
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;
    private static final int REQUEST_SELECT_FILE = 4;
    private static final int REQUEST_SETTINGS = 5;
    private static final int REQUEST_CONNECT_DEVICE_USB = 6;
    private static final int REQUEST_GRAPH_DISPLAY_DONE = 7;
    private static final int REQUEST_BT_PERMISSIONS = 8;
    private static final int REQUEST_NOTIFICATIONS = 9;

    /**
     * Priority PIDs for automatic dashboard / chart quick-launch when no items are checked
     */
    private static final int[] PRIORITY_NUMERIC_PIDS = {
        0x0C, // Engine RPM
        0x0D, // Vehicle Speed
        0x05, // Coolant Temperature
        0x04, // Calculated Engine Load
        0x11, // Throttle Position
        0x42, // Control Module Voltage
        0x0F, // Intake Air Temperature
        0x0B  // Intake Manifold Pressure
    };

    private static final int EXIT_TIMEOUT = 2500;
    private static final int DISPLAY_UPDATE_TIME = 250;
    private static final String LOG_MASTER = "log_master";
    private static final String KEEP_SCREEN_ON = "keep_screen_on";
    private static final String ELM_CUSTOM_INIT_CMDS = "elm_custom_init_cmds";

    private static final Logger rootLogger = Logger.getLogger("");
    private static final Logger log = Logger.getLogger(TAG);
    private static Timer updateTimer;
    private static final Set<String> emptyStringSet = new HashSet<>();

    /**
     * Container for Plugin-provided data
     */
    public static PvList mPluginPvs = new PvList();

    static SharedPreferences prefs;
    private static AlertDialog.Builder dlgBuilder;
    private static BluetoothAdapter mBluetoothAdapter = null;
    private static String mConnectedDeviceName = null;
    private static Menu menu;

    private static ObdItemAdapter mPidAdapter;
    private static VidItemAdapter mVidAdapter;
    private static TidItemAdapter mTidAdapter;
    private static DfcItemAdapter mDfcAdapter;
    private static PluginDataAdapter mPluginDataAdapter;
    private static ObdItemAdapter currDataAdapter;

    private static boolean initialBtStateEnabled = true;
    private static long lastBackPressTime = 0;
    private static Toast exitToast = null;
    private static boolean ignoreNrcs = false;

    private final AdapterView.OnItemSelectedListener ff_selected = new AdapterView.OnItemSelectedListener()
    {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
        {
            CommService.elm.setFreezeFrame_Id(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent)
        {
        }
    };

    private CommService mCommService = null;
    private FileHelper fileHelper;
    private View mListView;
    private DATA_VIEW_MODE dataViewMode = DATA_VIEW_MODE.LIST;
    private AutoHider toolbarAutoHider;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FileHandler logFileHandler;
    private int obdService = ElmProt.OBD_SVC_NONE;
    private MODE mode = MODE.OFFLINE;
    private int lastNumCodes = 0;
    private boolean pendingConnectAfterPermission = false;
    private boolean pendingConnectAfterBtEnable = false;

    private static final int MAX_AUTO_RECONNECT_ATTEMPTS = 5;
    private static final long[] AUTO_RECONNECT_DELAYS_MS = {1500L, 2000L, 2500L, 3000L, 4000L};
    private boolean userRequestedDisconnect = false;
    private boolean hadEstablishedConnection = false;
    private boolean autoReconnecting = false;
    private int autoReconnectAttempt = 0;
    private int serviceBeforeReconnect = ObdProt.OBD_SVC_DATA;
    private String lastConnectAddress = null;
    private int lastConnectPort = DEFAULT_WIFI_PORT;
    private boolean lastConnectSecure = false;

    private final Runnable autoReconnectRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            performAutoReconnectStep();
        }
    };

    private static final int MAX_CLIPBOARD_CHARS = 400_000;
    static final LiveDataRecorder liveDataRecorder = new LiveDataRecorder(new LiveDataRecorder.DataSource()
    {
        @Override
        public java.util.Collection<?> getPidItems()
        {
            return ObdProt.PidPvs != null ? ObdProt.PidPvs.values() : null;
        }

        @Override
        public java.util.Collection<?> getPluginItems()
        {
            return mPluginPvs != null ? mPluginPvs.values() : null;
        }
    });

    @SuppressLint("HandlerLeak")
    private transient final Handler mHandler = new Handler(Looper.getMainLooper())
    {
        @Override
        public void handleMessage(Message msg)
        {
            try
            {
                PropertyChangeEvent evt;
                log.log(Level.FINEST, String.format(Locale.US, "Handler notification: %s", msg));

                switch (msg.what)
                {
                    case MESSAGE_STATE_CHANGE:
                        log.log(Level.FINEST, String.format(Locale.US, "State change: %s", msg));
                        if (msg.arg2 != 0
                                && (mCommService == null || System.identityHashCode(mCommService) != msg.arg2))
                        {
                            // Ignore late callback from an older, replaced CommService instance
                            break;
                        }
                        switch ((CommService.STATE) msg.obj)
                        {
                            case CONNECTED:
                                onConnect();
                                break;

                            case CONNECTING:
                                setStatus(R.string.title_connecting);
                                break;

                            default:
                                onDisconnect();
                                break;
                        }
                        break;

                    case MESSAGE_FILE_WRITTEN:
                        break;

                    case MESSAGE_FILE_READ:
                        setDataListeners();
                        mPidAdapter.setPvList(ObdProt.PidPvs);
                        mVidAdapter.setPvList(ObdProt.VidPvs);
                        mTidAdapter.setPvList(ObdProt.VidPvs);
                        mDfcAdapter.setPvList(ObdProt.tCodes);
                        int loadedSvc = CommService.elm.getService();
                        if (loadedSvc == ObdProt.OBD_SVC_NONE)
                        {
                            loadedSvc = ObdProt.OBD_SVC_DATA;
                        }
                        setObdService(loadedSvc, getString(R.string.saved_data));
                        if (obdService == ObdProt.OBD_SVC_DATA)
                        {
                            checkToRestoreLastDataSelection();
                            checkToRestoreLastViewMode();
                        }
                        refreshAllUi();
                        break;

                    case MESSAGE_DEVICE_NAME:
                        mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.connected_to) + mConnectedDeviceName,
                                Toast.LENGTH_SHORT).show();
                        break;

                    case MESSAGE_TOAST:
                        if (!autoReconnecting)
                        {
                            Toast.makeText(getApplicationContext(),
                                    msg.getData().getString(TOAST),
                                    Toast.LENGTH_SHORT).show();
                        }
                        break;

                    case MESSAGE_DATA_ITEMS_CHANGED:
                        PvChangeEvent event = (PvChangeEvent) msg.obj;
                        switch (event.getType())
                        {
                            case PvChangeEvent.PV_ADDED:
                                currDataAdapter.setPvList(currDataAdapter.pvs);
                                try
                                {
                                    if (event.getSource() == ObdProt.PidPvs)
                                    {
                                        applyActivePresetPolling();
                                        checkToRestoreLastDataSelection();
                                        checkToRestoreLastViewMode();
                                    }
                                } catch (Exception e)
                                {
                                    log.log(Level.FINER, "Error adding PV", e);
                                }
                                refreshDataListStats();
                                break;

                            case PvChangeEvent.PV_CLEARED:
                                currDataAdapter.clear();
                                refreshDataListStats();
                                break;
                        }
                        break;

                    case MESSAGE_UPDATE_VIEW:
                        if (getListView() != null)
                        {
                            getListView().invalidateViews();
                        }
                        refreshDataListStats();
                        refreshTelemetryBadges();
                        break;

                    case MESSAGE_OBD_STATE_CHANGED:
                        evt = (PropertyChangeEvent) msg.obj;
                        ElmProt.STAT state = (ElmProt.STAT) evt.getNewValue();
                        if (getMode() != MODE.DEMO)
                        {
                            setStatus(getResources().getStringArray(R.array.elmcomm_states)[state.ordinal()]);
                        }
                        if (state == ElmProt.STAT.ECU_DETECTED)
                        {
                            if (istRestoreWanted(PRESELECT.LAST_SERVICE))
                            {
                                int restoredSvc = prefs.getInt(PRESELECT.LAST_SERVICE.toString(), ObdProt.OBD_SVC_DATA);
                                setObdService(restoredSvc != ObdProt.OBD_SVC_NONE ? restoredSvc : ObdProt.OBD_SVC_DATA, null);
                            } else if (obdService == ObdProt.OBD_SVC_NONE)
                            {
                                setObdService(ObdProt.OBD_SVC_DATA, getString(R.string.obd_data));
                            } else
                            {
                                CommService.elm.setService(obdService,
                                        (getMode() != MODE.FILE && getMode() != MODE.OFFLINE));
                                applyActivePresetPolling();
                            }
                        }
                        break;

                    case MESSAGE_OBD_NUMCODES:
                        evt = (PropertyChangeEvent) msg.obj;
                        setNumCodes((Integer) evt.getNewValue());
                        break;

                    case MESSAGE_OBD_ECUS:
                        evt = (PropertyChangeEvent) msg.obj;
                        //noinspection unchecked
                        selectEcu((Set<Integer>) evt.getNewValue());
                        break;

                    case MESSAGE_OBD_NRC:
                        if (!ignoreNrcs)
                        {
                            evt = (PropertyChangeEvent) msg.obj;
                            ObdProt.NRC nrc = (ObdProt.NRC) evt.getOldValue();
                            String nrcMsg = (String) evt.getNewValue();
                            switch (nrc.disp)
                            {
                                case ERROR:
                                    dlgBuilder
                                            .setIcon(android.R.drawable.ic_dialog_alert)
                                            .setTitle(R.string.obd_error)
                                            .setMessage(nrcMsg)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .setNegativeButton(null, null)
                                            .show();
                                    break;
                                case WARN:
                                    dlgBuilder
                                            .setIcon(android.R.drawable.ic_dialog_info)
                                            .setTitle(R.string.obd_error)
                                            .setMessage(nrcMsg)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .setNegativeButton(null, null)
                                            .show();
                                    break;
                                case NOTIFY:
                                    Toast.makeText(getApplicationContext(),
                                            nrcMsg,
                                            Toast.LENGTH_SHORT).show();
                                    break;
                                case HIDE:
                                default:
                                    break;
                            }
                        }
                        break;

                    case MESSAGE_TOOLBAR_VISIBLE:
                        Boolean visible = (Boolean) msg.obj;
                        log.fine(String.format(Locale.US, "ActionBar: %s", visible ? "show" : "hide"));
                        final ActionBar ab = getSupportActionBar();
                        if (ab != null)
                        {
                            if (visible)
                            {
                                ab.show();
                            } else
                            {
                                ab.hide();
                            }
                        }
                        break;
                }
            } catch (Exception ex)
            {
                log.log(Level.SEVERE, "Error in mHandler", ex);
            }
        }
    };

    /**
     * Set fixed PIDs for protocol to specified list of PIDs
     */
    public static void setFixedPids(Set<Integer> pidNumbers)
    {
        int[] pids = new int[pidNumbers.size()];
        int i = 0;
        for (Integer pidNum : pidNumbers)
        {
            pids[i++] = pidNum;
        }
        Arrays.sort(pids);
        ObdProt.setFixedPid(pids);
    }

    /**
     * Resolve the configured real communication medium from SharedPreferences.
     * Demo mode is a separate operating mode triggered by its own button, NEVER an adapter type:
     * any legacy "0" (DEMO) or invalid preference value is migrated to BLUETOOTH ("1").
     */
    static CommService.MEDIUM resolveRealMedium(SharedPreferences prefs)
    {
        if (prefs == null)
        {
            return CommService.MEDIUM.BLUETOOTH;
        }
        String raw = prefs.getString(SettingsActivity.KEY_COMM_MEDIUM, null);
        if (raw != null)
        {
            try
            {
                int idx = Integer.parseInt(raw.trim());
                CommService.MEDIUM[] values = CommService.MEDIUM.values();
                if (idx > 0 && idx < values.length)
                {
                    return values[idx];
                }
            } catch (NumberFormatException ignored)
            {
            }
            prefs.edit()
                    .putString(SettingsActivity.KEY_COMM_MEDIUM,
                            String.valueOf(CommService.MEDIUM.BLUETOOTH.ordinal()))
                    .apply();
        }
        return CommService.MEDIUM.BLUETOOTH;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        SettingsActivity.applyLocale(this);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        if (prefs.getBoolean(PREF_AUTOHIDE, false)
                && prefs.getBoolean(PREF_OVERLAY, false))
        {
            getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(PRIORITY_DEFAULT, new OnBackInvokedCallback()
            {
                @Override
                public void onBackInvoked()
                {
                    onBackPressed();
                }
            });
        }

        applyThemeMode(false);

        dlgBuilder = new AlertDialog.Builder(this);

        mPidAdapter = new ObdItemAdapter(this, R.layout.obd_item, ObdProt.PidPvs);
        mPidAdapter.setIncludePluginPvs(true);
        mPidAdapter.setEnableFavorites(true);
        mPidAdapter.setOnFavoritesChangedListener(() -> {
            applyActivePresetPolling();
            refreshDataListStats();
        });
        mVidAdapter = new VidItemAdapter(this, R.layout.obd_item, ObdProt.VidPvs);
        mTidAdapter = new TidItemAdapter(this, R.layout.obd_item, ObdProt.VidPvs);
        mDfcAdapter = new DfcItemAdapter(this, R.layout.obd_item, ObdProt.tCodes);
        mPluginDataAdapter = new PluginDataAdapter(this, R.layout.obd_item, mPluginPvs);
        currDataAdapter = mPidAdapter;

        ViewGroup contentRoot = findViewById(android.R.id.content);
        mListView = getLayoutInflater().inflate(R.layout.obd_list, contentRoot, false);
        initDataListControls();

        log.info(String.format(Locale.US, "%s %s starting",
                getString(R.string.app_name),
                getString(R.string.app_version)));

        onSharedPreferenceChanged(prefs, null);

        setupLoggers();

        fileHelper = new FileHelper(this);
        setDataListeners();
        CommService.elm.addPropertyChangeListener(this);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.show();
        }
        setAutoHider(prefs.getBoolean(PREF_AUTOHIDE, false));

        setContentView(R.layout.startup_layout);
        bindStartupLayout();
        setStatus(R.string.status_offline);

        invalidateOptionsMenu();

        if ("android.intent.action.VIEW".equals(getIntent().getAction()))
        {
            setMode(MODE.FILE);
            Uri uri = getIntent().getData();
            if (uri != null)
            {
                loadDataFromUri(uri);
            }
            return;
        }

        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(getIntent().getAction()))
        {
            selectRealMedium(CommService.MEDIUM.USB);
            startRealConnection();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
            {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            }
        }

        initSelectedMode();
    }

    /**
     * Initialize startup state WITHOUT auto-starting Demo mode.
     * Auto-connects only if the user explicitly enabled LAST_DEV_ADDRESS auto-restore.
     */
    @SuppressLint("MissingPermission")
    protected void initSelectedMode()
    {
        try
        {
            CommService.medium = resolveRealMedium(prefs);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter != null)
            {
                try
                {
                    initialBtStateEnabled = mBluetoothAdapter.isEnabled();
                } catch (SecurityException ignored)
                {
                    initialBtStateEnabled = true;
                }
            }

            if ((CommService.medium == CommService.MEDIUM.BLUETOOTH
                    || CommService.medium == CommService.MEDIUM.BLE)
                    && istRestoreWanted(PRESELECT.LAST_DEV_ADDRESS)
                    && prefs.getString(PRESELECT.LAST_DEV_ADDRESS.toString(), null) != null)
            {
                startRealConnection();
            } else
            {
                refreshAllUi();
            }
        } catch (Exception ex)
        {
            log.log(Level.WARNING, "initSelectedMode:", ex);
        }
    }

    /**
     * Collect missing runtime Bluetooth/Location permissions required to scan and connect.
     */
    private String[] getMissingBtPermissions()
    {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
            {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
            {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else
        {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
            {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        return missing.toArray(new String[0]);
    }

    /**
     * Update selected real adapter medium and persist it to preferences.
     */
    private void selectRealMedium(CommService.MEDIUM newMedium)
    {
        if (newMedium == null || newMedium == CommService.MEDIUM.DEMO)
        {
            newMedium = CommService.MEDIUM.BLUETOOTH;
        }
        CommService.medium = newMedium;
        prefs.edit()
                .putString(SettingsActivity.KEY_COMM_MEDIUM, String.valueOf(newMedium.ordinal()))
                .apply();
        refreshHomeUi();
    }

    /**
     * Initiate a REAL OBD-II adapter connection (Bluetooth, BLE, Wi-Fi, or USB).
     * Never enters Demo mode.
     */
    @SuppressLint("MissingPermission")
    private void startRealConnection()
    {
        cancelAutoReconnect();
        userRequestedDisconnect = false;
        hadEstablishedConnection = false;

        // Stop Demo mode cleanly if it was active
        if (getMode() == MODE.DEMO || ElmProt.runDemo)
        {
            stopDemoService();
            ObdProt.PidPvs.clear();
            ObdProt.VidPvs.clear();
            ObdProt.tCodes.clear();
            this.mode = MODE.OFFLINE;
        }

        if (mCommService != null)
        {
            mCommService.stop();
            mCommService = null;
        }

        CommService.medium = resolveRealMedium(prefs);

        switch (CommService.medium)
        {
            case BLUETOOTH:
            case BLE:
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (mBluetoothAdapter == null)
                {
                    Toast.makeText(this, R.string.bt_not_supported, Toast.LENGTH_LONG).show();
                    setMode(MODE.OFFLINE);
                    return;
                }

                String[] missingPerms = getMissingBtPermissions();
                if (missingPerms.length > 0)
                {
                    pendingConnectAfterPermission = true;
                    ActivityCompat.requestPermissions(this, missingPerms, REQUEST_BT_PERMISSIONS);
                    return;
                }

                if (!mBluetoothAdapter.isEnabled())
                {
                    pendingConnectAfterBtEnable = true;
                    Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
                    return;
                }

                ObdItemAdapter.allowDataUpdates = true;
                this.mode = MODE.ONLINE;
                setStatus(R.string.title_connecting);
                updateMenuVisibility();
                refreshAllUi();

                boolean secure = prefs.getBoolean("bt_secure_connection", false);
                String savedAddress = prefs.getString(PRESELECT.LAST_DEV_ADDRESS.toString(), null);
                if (istRestoreWanted(PRESELECT.LAST_DEV_ADDRESS)
                        && savedAddress != null && !savedAddress.isEmpty())
                {
                    if (CommService.medium == CommService.MEDIUM.BLE)
                    {
                        connectBleDevice(savedAddress, secure);
                    } else
                    {
                        connectBtDevice(savedAddress, secure);
                    }
                } else
                {
                    Intent pickerIntent = new Intent(this,
                            CommService.medium == CommService.MEDIUM.BLE
                                    ? BleDeviceListActivity.class
                                    : BtDeviceListActivity.class);
                    startActivityForResult(pickerIntent,
                            secure ? REQUEST_CONNECT_DEVICE_SECURE : REQUEST_CONNECT_DEVICE_INSECURE);
                }
                break;

            case USB:
                ObdItemAdapter.allowDataUpdates = true;
                this.mode = MODE.ONLINE;
                setStatus(R.string.title_connecting);
                updateMenuVisibility();
                refreshAllUi();
                Intent usbIntent = new Intent(this, UsbDeviceListActivity.class);
                startActivityForResult(usbIntent, REQUEST_CONNECT_DEVICE_USB);
                break;

            case NETWORK:
                showWifiConnectDialog();
                break;

            default:
                selectRealMedium(CommService.MEDIUM.BLUETOOTH);
                startRealConnection();
                break;
        }
    }

    /**
     * Show clean Wi-Fi adapter IP & Port confirmation dialog before connecting.
     */
    private void showWifiConnectDialog()
    {
        String currentIp = prefs.getString(DEVICE_ADDRESS, DEFAULT_WIFI_ADDRESS);
        if (currentIp == null || currentIp.trim().isEmpty())
        {
            currentIp = DEFAULT_WIFI_ADDRESS;
        }
        int currentPort = getPrefsInt(DEVICE_PORT, DEFAULT_WIFI_PORT);

        float density = getResources().getDisplayMetrics().density;
        int padH = Math.round(20 * density);
        int padV = Math.round(10 * density);
        int fieldPadH = Math.round(12 * density);
        int fieldPadV = Math.round(9 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padH, padV, padH, Math.round(4 * density));

        final EditText etIp = new EditText(this);
        etIp.setHint(R.string.device_address);
        etIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etIp.setSingleLine(true);
        etIp.setBackgroundResource(R.drawable.bg_search_box);
        etIp.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        etIp.setHintTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        etIp.setPadding(fieldPadH, fieldPadV, fieldPadH, fieldPadV);
        etIp.setText(currentIp);
        container.addView(etIp);

        final EditText etPort = new EditText(this);
        etPort.setHint(R.string.device_port);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setSingleLine(true);
        etPort.setBackgroundResource(R.drawable.bg_search_box);
        etPort.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        etPort.setHintTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        etPort.setPadding(fieldPadH, fieldPadV, fieldPadH, fieldPadV);
        etPort.setText(String.valueOf(currentPort));
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        portLp.topMargin = padV;
        etPort.setLayoutParams(portLp);
        container.addView(etPort);

        new AlertDialog.Builder(this)
                .setTitle(R.string.wifi_connect_title)
                .setView(container)
                .setPositiveButton(R.string.wifi_connect_action, (dialog, which) -> {
                    String ip = etIp.getText().toString().trim();
                    if (ip.isEmpty()) ip = DEFAULT_WIFI_ADDRESS;
                    int port = DEFAULT_WIFI_PORT;
                    try
                    {
                        port = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (NumberFormatException ignored)
                    {
                    }
                    prefs.edit()
                            .putString(DEVICE_ADDRESS, ip)
                            .putString(DEVICE_PORT, String.valueOf(port))
                            .apply();
                    ObdItemAdapter.allowDataUpdates = true;
                    this.mode = MODE.ONLINE;
                    setStatus(R.string.title_connecting);
                    updateMenuVisibility();
                    refreshAllUi();
                    connectNetworkDevice(ip, port);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Explicitly start Demo simulation mode (only via dedicated Demo button / menu action).
     */
    private void startDemoMode()
    {
        cancelAutoReconnect();
        userRequestedDisconnect = false;
        hadEstablishedConnection = false;
        if (mCommService != null)
        {
            mCommService.stop();
            mCommService = null;
        }
        setMode(MODE.DEMO);
        setObdService(ObdProt.OBD_SVC_DATA, getString(R.string.obd_data));
    }

    /**
     * Disconnect any active adapter or stop Demo mode and return cleanly to Offline home screen.
     */
    private void disconnectSession()
    {
        userRequestedDisconnect = true;
        hadEstablishedConnection = false;
        cancelAutoReconnect();
        if (mCommService != null)
        {
            mCommService.stop();
            mCommService = null;
        }
        stopDemoService();
        CommService.elm.setService(ObdProt.OBD_SVC_NONE, false);
        obdService = ObdProt.OBD_SVC_NONE;
        dataViewMode = DATA_VIEW_MODE.LIST;
        setMode(MODE.OFFLINE);
        setContentView(R.layout.startup_layout);
        bindStartupLayout();
        final ActionBar ab = getSupportActionBar();
        if (ab != null)
        {
            ab.setDisplayHomeAsUpEnabled(false);
            ab.setTitle(R.string.app_name);
        }
    }

    private void cancelAutoReconnect()
    {
        autoReconnecting = false;
        autoReconnectAttempt = 0;
        mHandler.removeCallbacks(autoReconnectRunnable);
    }

    @SuppressLint("MissingPermission")
    private void performAutoReconnectStep()
    {
        if (!autoReconnecting || userRequestedDisconnect || getMode() != MODE.ONLINE)
        {
            return;
        }
        try
        {
            if (mCommService != null)
            {
                mCommService.stop();
                mCommService = null;
            }

            switch (CommService.medium)
            {
                case BLUETOOTH:
                    if (lastConnectAddress != null && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled())
                    {
                        connectBtDevice(lastConnectAddress, lastConnectSecure);
                        return;
                    }
                    break;

                case BLE:
                    if (lastConnectAddress != null && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled())
                    {
                        connectBleDevice(lastConnectAddress, lastConnectSecure);
                        return;
                    }
                    break;

                case NETWORK:
                    String host = (lastConnectAddress != null)
                            ? lastConnectAddress
                            : prefs.getString(DEVICE_ADDRESS, DEFAULT_WIFI_ADDRESS);
                    int port = (lastConnectPort > 0)
                            ? lastConnectPort
                            : getPrefsInt(DEVICE_PORT, DEFAULT_WIFI_PORT);
                    connectNetworkDevice(host, port);
                    return;

                case USB:
                    if (UsbDeviceListActivity.selectedPort != null)
                    {
                        mCommService = new UsbCommService(this, mHandler);
                        mCommService.connect(UsbDeviceListActivity.selectedPort, true);
                        return;
                    }
                    break;

                default:
                    break;
            }
        } catch (Exception ex)
        {
            log.log(Level.WARNING, "Auto-reconnect attempt failed", ex);
        }

        // Could not initiate transport reconnect
        cancelAutoReconnect();
        setMode(MODE.OFFLINE);
    }

    /**
     * Wire up click listeners and initial state on startup_layout.xml
     */
    private void bindStartupLayout()
    {
        View chipBt = findViewById(R.id.chip_medium_bt);
        View chipBle = findViewById(R.id.chip_medium_ble);
        View chipWifi = findViewById(R.id.chip_medium_wifi);
        View chipUsb = findViewById(R.id.chip_medium_usb);

        if (chipBt != null) chipBt.setOnClickListener(v -> selectRealMedium(CommService.MEDIUM.BLUETOOTH));
        if (chipBle != null) chipBle.setOnClickListener(v -> selectRealMedium(CommService.MEDIUM.BLE));
        if (chipWifi != null) chipWifi.setOnClickListener(v -> selectRealMedium(CommService.MEDIUM.NETWORK));
        if (chipUsb != null) chipUsb.setOnClickListener(v -> selectRealMedium(CommService.MEDIUM.USB));

        View btnConnectReal = findViewById(R.id.btn_connect_real);
        if (btnConnectReal != null)
        {
            btnConnectReal.setOnClickListener(v -> {
                if (getMode() == MODE.ONLINE)
                {
                    setObdService(ObdProt.OBD_SVC_DATA, getString(R.string.obd_data));
                } else
                {
                    startRealConnection();
                }
            });
        }

        View btnStartDemo = findViewById(R.id.btn_start_demo);
        if (btnStartDemo != null)
        {
            btnStartDemo.setOnClickListener(v -> {
                if (getMode() == MODE.DEMO)
                {
                    setObdService(ObdProt.OBD_SVC_DATA, getString(R.string.obd_data));
                } else
                {
                    startDemoMode();
                }
            });
        }

        View btnDisconnectHome = findViewById(R.id.btn_disconnect_home);
        if (btnDisconnectHome != null)
        {
            btnDisconnectHome.setOnClickListener(v -> disconnectSession());
        }

        View btnCopyRecordHome = findViewById(R.id.btn_copy_record_home);
        if (btnCopyRecordHome != null)
        {
            btnCopyRecordHome.setOnClickListener(v -> copyLiveRecordingToClipboard());
        }

        View cardData = findViewById(R.id.card_nav_data);
        if (cardData != null)
        {
            cardData.setOnClickListener(v -> openServiceFromHome(ObdProt.OBD_SVC_DATA, R.string.obd_data));
        }

        View cardCodes = findViewById(R.id.card_nav_codes);
        if (cardCodes != null)
        {
            cardCodes.setOnClickListener(v -> openServiceFromHome(ObdProt.OBD_SVC_READ_CODES, R.string.obd_codes));
        }

        View cardVeh = findViewById(R.id.card_nav_veh);
        if (cardVeh != null)
        {
            cardVeh.setOnClickListener(v -> openServiceFromHome(ObdProt.OBD_SVC_VEH_INFO, R.string.obd_veh_info));
        }

        View cardLoad = findViewById(R.id.card_nav_load);
        if (cardLoad != null)
        {
            cardLoad.setOnClickListener(v -> {
                setMode(MODE.FILE);
                selectFileToLoad();
            });
        }

        refreshHomeUi();
    }

    private void openServiceFromHome(int svc, int titleRes)
    {
        if (getMode() == MODE.OFFLINE)
        {
            Toast.makeText(this, R.string.connect_first_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        setObdService(svc, getString(titleRes));
    }

    /**
     * Wire up interactive controls on obd_list.xml (service tabs, search bar, 1-tap gauges/chart)
     */
    private void initDataListControls()
    {
        if (mListView == null) return;

        View btnHome = mListView.findViewById(R.id.btn_nav_home);
        if (btnHome != null)
        {
            btnHome.setOnClickListener(v -> setObdService(ObdProt.OBD_SVC_NONE, null));
        }

        View btnDisconnect = mListView.findViewById(R.id.btn_header_disconnect);
        if (btnDisconnect != null)
        {
            btnDisconnect.setOnClickListener(v -> disconnectSession());
        }

        View milBadge = mListView.findViewById(R.id.mil_badge_container);
        if (milBadge != null)
        {
            milBadge.setOnClickListener(v ->
                    setObdService(ObdProt.OBD_SVC_READ_CODES, getString(R.string.obd_codes)));
        }

        View tabData = mListView.findViewById(R.id.tab_svc_data);
        if (tabData != null)
        {
            tabData.setOnClickListener(v ->
                    setObdService(ObdProt.OBD_SVC_DATA, getString(R.string.obd_data)));
        }

        View tabCodes = mListView.findViewById(R.id.tab_svc_codes);
        if (tabCodes != null)
        {
            tabCodes.setOnClickListener(v ->
                    setObdService(ObdProt.OBD_SVC_READ_CODES, getString(R.string.obd_codes)));
        }

        View tabInfo = mListView.findViewById(R.id.tab_svc_info);
        if (tabInfo != null)
        {
            tabInfo.setOnClickListener(v ->
                    setObdService(ObdProt.OBD_SVC_VEH_INFO, getString(R.string.obd_veh_info)));
        }

        View tabFf = mListView.findViewById(R.id.tab_svc_ff);
        if (tabFf != null)
        {
            tabFf.setOnClickListener(v ->
                    setObdService(ObdProt.OBD_SVC_FREEZEFRAME, getString(R.string.service_freezeframes)));
        }

        EditText etSearch = mListView.findViewById(R.id.et_pid_search);
        View btnClearSearch = mListView.findViewById(R.id.btn_clear_search);
        if (etSearch != null)
        {
            etSearch.addTextChangedListener(new TextWatcher()
            {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after)
                {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count)
                {
                    String q = s != null ? s.toString() : "";
                    if (btnClearSearch != null)
                    {
                        btnClearSearch.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    if (getListView() != null && getListView().getCheckedItemCount() > 0)
                    {
                        getListView().clearChoices();
                    }
                    mPidAdapter.setSearchQuery(q);
                    refreshDataListStats();
                }

                @Override
                public void afterTextChanged(Editable s)
                {
                }
            });
        }
        if (btnClearSearch != null && etSearch != null)
        {
            btnClearSearch.setOnClickListener(v -> etSearch.setText(""));
        }

        View btnQuickGauges = mListView.findViewById(R.id.btn_quick_gauges);
        if (btnQuickGauges != null)
        {
            btnQuickGauges.setOnClickListener(v -> launchQuickVisualization(DATA_VIEW_MODE.DASHBOARD));
        }

        View btnQuickChart = mListView.findViewById(R.id.btn_quick_chart);
        if (btnQuickChart != null)
        {
            btnQuickChart.setOnClickListener(v -> launchQuickVisualization(DATA_VIEW_MODE.CHART));
        }

        View btnQuickHud = mListView.findViewById(R.id.btn_quick_hud);
        if (btnQuickHud != null)
        {
            btnQuickHud.setOnClickListener(v -> launchQuickVisualization(DATA_VIEW_MODE.HEADUP));
        }

        View btnLiveRecord = mListView.findViewById(R.id.btn_live_record);
        if (btnLiveRecord != null)
        {
            btnLiveRecord.setOnClickListener(v -> toggleLiveRecording());
        }

        View btnLiveMark = mListView.findViewById(R.id.btn_live_mark);
        if (btnLiveMark != null)
        {
            btnLiveMark.setOnClickListener(v -> addLiveRecordingMarker());
        }

        View btnCopyLiveRecord = mListView.findViewById(R.id.btn_copy_live_record);
        if (btnCopyLiveRecord != null)
        {
            btnCopyLiveRecord.setOnClickListener(v -> copyLiveRecordingToClipboard());
        }

        View chipAll = mListView.findViewById(R.id.chip_preset_all);
        View chipFav = mListView.findViewById(R.id.chip_preset_fav);
        View chipStartup = mListView.findViewById(R.id.chip_preset_startup);
        View chipFuel = mListView.findViewById(R.id.chip_preset_fuel);
        View chipDriving = mListView.findViewById(R.id.chip_preset_driving);

        if (chipAll != null) chipAll.setOnClickListener(v -> selectPidPreset(PidPresetManager.Preset.ALL));
        if (chipFav != null) chipFav.setOnClickListener(v -> selectPidPreset(PidPresetManager.Preset.FAVORITES));
        if (chipStartup != null) chipStartup.setOnClickListener(v -> selectPidPreset(PidPresetManager.Preset.STARTUP));
        if (chipFuel != null) chipFuel.setOnClickListener(v -> selectPidPreset(PidPresetManager.Preset.FUEL));
        if (chipDriving != null) chipDriving.setOnClickListener(v -> selectPidPreset(PidPresetManager.Preset.DRIVING));

        View btnCopyDtcReport = mListView.findViewById(R.id.btn_copy_dtc_report);
        if (btnCopyDtcReport != null)
        {
            btnCopyDtcReport.setOnClickListener(v -> copyFaultCodeReportToClipboard());
        }

        View btnClearCodesBar = mListView.findViewById(R.id.btn_clear_codes_bar);
        if (btnClearCodesBar != null)
        {
            btnClearCodesBar.setOnClickListener(v -> clearObdFaultCodes());
        }

        refreshPresetChipsUi();
    }

    private void selectPidPreset(PidPresetManager.Preset preset)
    {
        if (getListView() != null && getListView().getCheckedItemCount() > 0)
        {
            getListView().clearChoices();
        }
        mPidAdapter.setPreset(preset);
        applyActivePresetPolling();
        refreshPresetChipsUi();
        refreshDataListStats();
    }

    private void applyActivePresetPolling()
    {
        if (obdService != ObdProt.OBD_SVC_DATA || dataViewMode != DATA_VIEW_MODE.LIST)
        {
            return;
        }
        PidPresetManager.Preset preset = mPidAdapter.getPreset();
        TreeSet<Integer> fixed = PidPresetManager.computeFixedPids(
                preset,
                ObdProt.PidPvs.values(),
                PidPresetManager.loadFavorites(prefs));
        ObdProt.resetFixedPid();
        if (!fixed.isEmpty())
        {
            setFixedPids(fixed);
        }
    }

    private void refreshPresetChipsUi()
    {
        if (mListView == null || mPidAdapter == null) return;
        PidPresetManager.Preset p = mPidAdapter.getPreset();

        View chipAll = mListView.findViewById(R.id.chip_preset_all);
        View chipFav = mListView.findViewById(R.id.chip_preset_fav);
        View chipStartup = mListView.findViewById(R.id.chip_preset_startup);
        View chipFuel = mListView.findViewById(R.id.chip_preset_fuel);
        View chipDriving = mListView.findViewById(R.id.chip_preset_driving);

        if (chipAll != null) chipAll.setSelected(p == PidPresetManager.Preset.ALL);
        if (chipFav != null) chipFav.setSelected(p == PidPresetManager.Preset.FAVORITES);
        if (chipStartup != null) chipStartup.setSelected(p == PidPresetManager.Preset.STARTUP);
        if (chipFuel != null) chipFuel.setSelected(p == PidPresetManager.Preset.FUEL);
        if (chipDriving != null) chipDriving.setSelected(p == PidPresetManager.Preset.DRIVING);
    }

    /**
     * Launch Dashboard / Chart / HUD either for user-checked items or automatically for top numeric PIDs.
     */
    private void launchQuickVisualization(DATA_VIEW_MODE targetMode)
    {
        int[] positions;
        if (getListView() != null && getListView().getCheckedItemCount() > 0)
        {
            positions = getSelectedPositions();
        } else
        {
            int maxItems = (targetMode == DATA_VIEW_MODE.CHART) ? 4 : 6;
            positions = findBestNumericPositions(maxItems);
        }

        if (positions.length == 0)
        {
            Toast.makeText(this, R.string.no_numeric_items_yet, Toast.LENGTH_SHORT).show();
            return;
        }

        switch (targetMode)
        {
            case DASHBOARD:
            case HEADUP:
                DashBoardActivity.setAdapter(getListAdapter());
                Intent dashIntent = new Intent(this, DashBoardActivity.class);
                dashIntent.putExtra(DashBoardActivity.POSITIONS, positions);
                dashIntent.putExtra(DashBoardActivity.RES_ID,
                        targetMode == DATA_VIEW_MODE.DASHBOARD ? R.layout.dashboard : R.layout.head_up);
                startActivityForResult(dashIntent, REQUEST_GRAPH_DISPLAY_DONE);
                this.dataViewMode = targetMode;
                break;

            case CHART:
                ChartActivity.setAdapter(getListAdapter());
                Intent chartIntent = new Intent(this, ChartActivity.class);
                chartIntent.putExtra(ChartActivity.POSITIONS, positions);
                chartIntent.putExtra(ChartActivity.LIVE_UPDATE,
                        Set.of(MODE.ONLINE, MODE.DEMO).contains(getMode()));
                startActivityForResult(chartIntent, REQUEST_GRAPH_DISPLAY_DONE);
                this.dataViewMode = targetMode;
                break;

            default:
                break;
        }
    }

    private int[] findBestNumericPositions(int maxCount)
    {
        if (currDataAdapter == null) return new int[0];
        ArrayList<Integer> chosen = new ArrayList<>(maxCount);

        // First pick priority driving telemetry PIDs if present
        for (int wantedPid : PRIORITY_NUMERIC_PIDS)
        {
            if (chosen.size() >= maxCount) break;
            for (int i = 0; i < currDataAdapter.getCount(); i++)
            {
                if (chosen.contains(i)) continue;
                Object obj = currDataAdapter.getItem(i);
                if (obj instanceof EcuDataPv)
                {
                    EcuDataPv pv = (EcuDataPv) obj;
                    if (pv.get(EcuDataPv.FID_VALUE) instanceof Number
                            && pv.getAsInt(EcuDataPv.FID_PID) == wantedPid)
                    {
                        chosen.add(i);
                        break;
                    }
                }
            }
        }

        // Fill remaining slots with any other numeric PIDs
        for (int i = 0; i < currDataAdapter.getCount() && chosen.size() < maxCount; i++)
        {
            if (chosen.contains(i)) continue;
            Object obj = currDataAdapter.getItem(i);
            if (obj instanceof EcuDataPv
                    && ((EcuDataPv) obj).get(EcuDataPv.FID_VALUE) instanceof Number)
            {
                chosen.add(i);
            }
        }

        int[] result = new int[chosen.size()];
        for (int i = 0; i < chosen.size(); i++)
        {
            result[i] = chosen.get(i);
        }
        return result;
    }

    private void refreshAllUi()
    {
        refreshHomeUi();
        refreshDataHeaderUi();
        refreshPresetChipsUi();
        refreshTelemetryBadges();
        refreshDataListStats();
        refreshLiveRecorderUi();
    }

    private void refreshHomeUi()
    {
        CommService.MEDIUM activeMedium = resolveRealMedium(prefs);

        View chipBt = findViewById(R.id.chip_medium_bt);
        View chipBle = findViewById(R.id.chip_medium_ble);
        View chipWifi = findViewById(R.id.chip_medium_wifi);
        View chipUsb = findViewById(R.id.chip_medium_usb);
        if (chipBt != null) chipBt.setSelected(activeMedium == CommService.MEDIUM.BLUETOOTH);
        if (chipBle != null) chipBle.setSelected(activeMedium == CommService.MEDIUM.BLE);
        if (chipWifi != null) chipWifi.setSelected(activeMedium == CommService.MEDIUM.NETWORK);
        if (chipUsb != null) chipUsb.setSelected(activeMedium == CommService.MEDIUM.USB);

        TextView tvHint = findViewById(R.id.tv_medium_hint);
        if (tvHint != null)
        {
            switch (activeMedium)
            {
                case BLE:
                    tvHint.setText(R.string.medium_hint_ble);
                    break;
                case NETWORK:
                    tvHint.setText(R.string.medium_hint_wifi);
                    break;
                case USB:
                    tvHint.setText(R.string.medium_hint_usb);
                    break;
                case BLUETOOTH:
                default:
                    tvHint.setText(R.string.medium_hint_bt);
                    break;
            }
        }

        TextView badge = findViewById(R.id.home_status_badge);
        if (badge != null)
        {
            switch (getMode())
            {
                case ONLINE:
                    badge.setText(R.string.status_online);
                    badge.setBackgroundResource(R.drawable.bg_badge_online);
                    badge.setTextColor(ContextCompat.getColor(this, R.color.status_online));
                    break;
                case DEMO:
                    badge.setText(R.string.status_demo);
                    badge.setBackgroundResource(R.drawable.bg_badge_demo);
                    badge.setTextColor(ContextCompat.getColor(this, R.color.status_demo));
                    break;
                case FILE:
                    badge.setText(R.string.status_file);
                    badge.setBackgroundResource(R.drawable.bg_badge_neutral);
                    badge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    break;
                case OFFLINE:
                default:
                    badge.setText(R.string.status_offline);
                    badge.setBackgroundResource(R.drawable.bg_badge_neutral);
                    badge.setTextColor(ContextCompat.getColor(this, R.color.status_offline));
                    break;
            }
        }

        ImageView ivConnectReal = findViewById(R.id.iv_btn_connect_real);
        if (ivConnectReal != null)
        {
            if (getMode() == MODE.ONLINE)
            {
                ivConnectReal.setImageResource(R.drawable.ic_visibility);
            } else
            {
                switch (activeMedium)
                {
                    case NETWORK:
                        ivConnectReal.setImageResource(R.drawable.ic_wifi);
                        break;
                    case USB:
                        ivConnectReal.setImageResource(R.drawable.ic_usb);
                        break;
                    case BLE:
                    case BLUETOOTH:
                    default:
                        ivConnectReal.setImageResource(R.drawable.ic_bluetooth);
                        break;
                }
            }
        }

        TextView tvConnectReal = findViewById(R.id.tv_btn_connect_real);
        if (tvConnectReal != null)
        {
            tvConnectReal.setText(getMode() == MODE.ONLINE
                    ? R.string.open_live_data
                    : R.string.connect_real_adapter);
        }

        float navAlpha = (getMode() == MODE.OFFLINE) ? 0.62f : 1.0f;
        View cardData = findViewById(R.id.card_nav_data);
        View cardCodes = findViewById(R.id.card_nav_codes);
        View cardVeh = findViewById(R.id.card_nav_veh);
        if (cardData != null) cardData.setAlpha(navAlpha);
        if (cardCodes != null) cardCodes.setAlpha(navAlpha);
        if (cardVeh != null) cardVeh.setAlpha(navAlpha);

        View btnStartDemo = findViewById(R.id.btn_start_demo);
        TextView tvStartDemo = findViewById(R.id.tv_btn_start_demo);
        if (btnStartDemo != null && tvStartDemo != null)
        {
            if (getMode() == MODE.ONLINE)
            {
                btnStartDemo.setVisibility(View.GONE);
            } else
            {
                btnStartDemo.setVisibility(View.VISIBLE);
                tvStartDemo.setText(getMode() == MODE.DEMO
                        ? R.string.open_live_data
                        : R.string.start_demo_mode);
            }
        }

        View btnDisconnectHome = findViewById(R.id.btn_disconnect_home);
        TextView tvDisconnectHome = findViewById(R.id.tv_btn_disconnect_home);
        if (btnDisconnectHome != null && tvDisconnectHome != null)
        {
            if (getMode() == MODE.OFFLINE)
            {
                btnDisconnectHome.setVisibility(View.GONE);
            } else
            {
                btnDisconnectHome.setVisibility(View.VISIBLE);
                tvDisconnectHome.setText(getMode() == MODE.DEMO
                        ? R.string.stop_demo_mode
                        : R.string.disconnect);
            }
        }

        View btnCopyRecordHome = findViewById(R.id.btn_copy_record_home);
        TextView tvCopyRecordHome = findViewById(R.id.tv_copy_record_home);
        if (btnCopyRecordHome != null && tvCopyRecordHome != null)
        {
            if (liveDataRecorder.hasSamples() && !liveDataRecorder.isRecording())
            {
                btnCopyRecordHome.setVisibility(View.VISIBLE);
                tvCopyRecordHome.setText(getString(
                        R.string.live_rec_copy_home_fmt,
                        liveDataRecorder.getSampleCount(),
                        LiveDataRecorder.formatDurationMmSs(liveDataRecorder.getDurationSeconds())));
            } else
            {
                btnCopyRecordHome.setVisibility(View.GONE);
            }
        }
    }

    private void refreshDataHeaderUi()
    {
        if (mListView == null) return;

        TextView tvSession = mListView.findViewById(R.id.tv_session_badge);
        if (tvSession != null)
        {
            switch (getMode())
            {
                case DEMO:
                    tvSession.setText(R.string.status_demo);
                    tvSession.setBackgroundResource(R.drawable.bg_badge_demo);
                    tvSession.setTextColor(ContextCompat.getColor(this, R.color.status_demo));
                    break;
                case FILE:
                    tvSession.setText(R.string.status_file);
                    tvSession.setBackgroundResource(R.drawable.bg_badge_neutral);
                    tvSession.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    break;
                case ONLINE:
                default:
                    if (autoReconnecting)
                    {
                        tvSession.setText(getString(R.string.status_reconnecting_fmt,
                                Math.max(1, autoReconnectAttempt), MAX_AUTO_RECONNECT_ATTEMPTS));
                        tvSession.setBackgroundResource(R.drawable.bg_badge_demo);
                        tvSession.setTextColor(ContextCompat.getColor(this, R.color.status_demo));
                    } else
                    {
                        tvSession.setText(R.string.status_online);
                        tvSession.setBackgroundResource(R.drawable.bg_badge_online);
                        tvSession.setTextColor(ContextCompat.getColor(this, R.color.status_online));
                    }
                    break;
            }
        }

        TextView btnDisconnect = mListView.findViewById(R.id.btn_header_disconnect);
        if (btnDisconnect != null)
        {
            btnDisconnect.setText(getMode() == MODE.DEMO
                    ? R.string.stop_demo_mode
                    : R.string.disconnect);
        }

        View milBadge = mListView.findViewById(R.id.mil_badge_container);
        ImageView ivMil = mListView.findViewById(R.id.iv_mil_icon);
        TextView tvMil = mListView.findViewById(R.id.tv_mil_status);
        boolean milActive = (lastNumCodes & 0x80) != 0;
        int dtcCount = lastNumCodes & 0x7F;
        if (milBadge != null && ivMil != null && tvMil != null)
        {
            ivMil.setImageResource(milActive ? R.drawable.mil_on : R.drawable.mil_off);
            if (milActive || dtcCount > 0)
            {
                milBadge.setBackgroundResource(R.drawable.bg_badge_danger);
                tvMil.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
                tvMil.setText(getString(R.string.mil_active_fmt, dtcCount));
            } else
            {
                milBadge.setBackgroundResource(R.drawable.bg_badge_neutral);
                tvMil.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tvMil.setText(R.string.mil_ok);
            }
        }

        View tabData = mListView.findViewById(R.id.tab_svc_data);
        View tabCodes = mListView.findViewById(R.id.tab_svc_codes);
        View tabInfo = mListView.findViewById(R.id.tab_svc_info);
        View tabFf = mListView.findViewById(R.id.tab_svc_ff);

        if (tabData != null) tabData.setSelected(obdService == ObdProt.OBD_SVC_DATA);
        if (tabCodes != null)
        {
            tabCodes.setSelected(obdService == ObdProt.OBD_SVC_READ_CODES
                    || obdService == ObdProt.OBD_SVC_PENDINGCODES
                    || obdService == ObdProt.OBD_SVC_PERMACODES);
        }
        if (tabInfo != null) tabInfo.setSelected(obdService == ObdProt.OBD_SVC_VEH_INFO);
        if (tabFf != null)
        {
            tabFf.setSelected(obdService == ObdProt.OBD_SVC_FREEZEFRAME);
            boolean ffAvailable = (lastNumCodes != 0)
                    || (ObdProt.tCodes != null && !ObdProt.tCodes.isEmpty());
            tabFf.setVisibility(ffAvailable || obdService == ObdProt.OBD_SVC_FREEZEFRAME
                    ? View.VISIBLE : View.GONE);
        }

        View liveToolbar = mListView.findViewById(R.id.live_data_toolbar);
        if (liveToolbar != null)
        {
            liveToolbar.setVisibility(obdService == ObdProt.OBD_SVC_DATA ? View.VISIBLE : View.GONE);
        }

        View codesToolbar = mListView.findViewById(R.id.codes_toolbar);
        View btnClearCodesBar = mListView.findViewById(R.id.btn_clear_codes_bar);
        if (codesToolbar != null)
        {
            boolean isCodesSvc = (obdService == ObdProt.OBD_SVC_READ_CODES
                    || obdService == ObdProt.OBD_SVC_PENDINGCODES
                    || obdService == ObdProt.OBD_SVC_PERMACODES);
            codesToolbar.setVisibility(isCodesSvc ? View.VISIBLE : View.GONE);
            if (btnClearCodesBar != null)
            {
                btnClearCodesBar.setVisibility(getMode() != MODE.FILE ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void refreshTelemetryBadges()
    {
        if (mListView == null) return;

        TextView tvVolt = mListView.findViewById(R.id.tv_voltage_badge);
        TextView tvRate = mListView.findViewById(R.id.tv_rate_badge);
        boolean liveMode = (getMode() == MODE.ONLINE || getMode() == MODE.DEMO);

        if (tvVolt != null)
        {
            double volts = liveMode
                    ? PidPresetManager.extractBoardVoltage(ObdProt.PidPvs.values(), mPluginPvs.values())
                    : Double.NaN;
            if (!Double.isNaN(volts))
            {
                tvVolt.setText(String.format(Locale.getDefault(), getString(R.string.voltage_badge_fmt), volts));
                boolean abnormal = (volts < 11.8 || volts > 15.2);
                tvVolt.setBackgroundResource(abnormal ? R.drawable.bg_badge_danger : R.drawable.bg_badge_neutral);
                tvVolt.setTextColor(ContextCompat.getColor(this,
                        abnormal ? R.color.status_danger : R.color.text_primary));
                tvVolt.setVisibility(View.VISIBLE);
            } else
            {
                tvVolt.setVisibility(View.GONE);
            }
        }

        if (tvRate != null)
        {
            double rate = (liveMode && !autoReconnecting)
                    ? ObdItemAdapter.pollRateTracker.getUpdatesPerSecond(System.currentTimeMillis())
                    : 0.0;
            if (rate >= 0.5)
            {
                tvRate.setText(String.format(Locale.getDefault(), getString(R.string.poll_rate_badge_fmt), rate));
                tvRate.setVisibility(View.VISIBLE);
            } else
            {
                tvRate.setVisibility(View.GONE);
            }
        }
    }

    private void refreshDataListStats()
    {
        if (mListView == null || currDataAdapter == null) return;

        int count = currDataAdapter.getCount();
        TextView tvCount = mListView.findViewById(R.id.tv_params_count);
        if (tvCount != null)
        {
            tvCount.setText(getString(R.string.params_count_fmt, count));
        }

        TextView tvEmpty = mListView.findViewById(R.id.tv_list_empty);
        if (tvEmpty != null)
        {
            if (count > 0)
            {
                tvEmpty.setVisibility(View.GONE);
            } else
            {
                tvEmpty.setVisibility(View.VISIBLE);
                if (obdService == ObdProt.OBD_SVC_READ_CODES
                        || obdService == ObdProt.OBD_SVC_PENDINGCODES
                        || obdService == ObdProt.OBD_SVC_PERMACODES)
                {
                    tvEmpty.setText(R.string.empty_no_codes);
                } else if (obdService == ObdProt.OBD_SVC_DATA
                        && mPidAdapter.getPreset() == PidPresetManager.Preset.FAVORITES
                        && PidPresetManager.loadFavorites(prefs).isEmpty())
                {
                    tvEmpty.setText(R.string.empty_no_favorites);
                } else if (obdService == ObdProt.OBD_SVC_DATA
                        && (!mPidAdapter.getSearchQuery().isEmpty()
                        || mPidAdapter.getPreset() != PidPresetManager.Preset.ALL))
                {
                    tvEmpty.setText(R.string.empty_no_search_match);
                } else
                {
                    tvEmpty.setText(R.string.empty_waiting_data);
                }
            }
        }
    }

    /**
     * Start or stop 1-second Live Data time-series recording.
     */
    private void toggleLiveRecording()
    {
        if (liveDataRecorder.isRecording())
        {
            stopLiveRecording(true);
        } else
        {
            startLiveRecording();
        }
    }

    private void startLiveRecording()
    {
        if (getMode() != MODE.ONLINE && getMode() != MODE.DEMO)
        {
            Toast.makeText(this, R.string.live_rec_connect_needed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (obdService != ObdProt.OBD_SVC_DATA)
        {
            setObdService(ObdProt.OBD_SVC_DATA, getString(R.string.obd_data));
        }

        liveDataRecorder.start(this::refreshLiveRecorderUi);
        syncKeepScreenOnFlag();
        updateMenuVisibility();
        refreshAllUi();
        Toast.makeText(this, R.string.live_rec_started_toast, Toast.LENGTH_SHORT).show();
    }

    private LiveDataRecorder.ExportLabels buildRecorderExportLabels()
    {
        return new LiveDataRecorder.ExportLabels(
                getString(R.string.live_rec_col_time),
                getString(R.string.live_rec_col_sec),
                getString(R.string.live_rec_col_mark),
                getString(R.string.live_rec_section_constant_fmt),
                getString(R.string.live_rec_section_dynamic_fmt),
                getString(R.string.live_rec_monitors_prefix_fmt),
                getString(R.string.live_rec_inactive_group),
                getString(R.string.live_rec_all_constant_note));
    }

    private void stopLiveRecording(boolean showSummaryDialog)
    {
        boolean wasRecording = liveDataRecorder.isRecording();
        liveDataRecorder.stop();
        syncKeepScreenOnFlag();
        updateMenuVisibility();
        refreshAllUi();

        if (!wasRecording)
        {
            return;
        }

        if (!liveDataRecorder.hasSamples())
        {
            if (showSummaryDialog)
            {
                Toast.makeText(this, R.string.live_rec_stopped_empty, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        final String csvText = liveDataRecorder.buildCsvTable(
                getString(R.string.live_rec_col_time),
                getString(R.string.live_rec_col_sec),
                getString(R.string.live_rec_col_mark));
        saveRecordedCsvInBackground(csvText);

        if (showSummaryDialog && !isFinishing())
        {
            showLiveRecordingFinishedDialog();
        }
    }

    private void addLiveRecordingMarker()
    {
        String mark = liveDataRecorder.addMarker();
        if (!mark.isEmpty())
        {
            refreshLiveRecorderUi();
            Toast.makeText(this,
                    getString(R.string.live_rec_mark_added_fmt, mark),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFaultCodeReportToClipboard()
    {
        DiagnosticReportBuilder.Labels labels = new DiagnosticReportBuilder.Labels(
                getString(R.string.dtc_report_title),
                getString(R.string.dtc_report_mil_on),
                getString(R.string.dtc_report_mil_off),
                getString(R.string.obd_veh_info),
                getString(R.string.obd_codes),
                getString(R.string.empty_no_codes),
                getString(R.string.dtc_status_confirmed),
                getString(R.string.dtc_status_pending),
                getString(R.string.dtc_status_permanent),
                getString(R.string.dtc_report_snapshot_section));

        String report = DiagnosticReportBuilder.buildFaultReport(
                System.currentTimeMillis(),
                lastNumCodes,
                ObdProt.tCodes != null ? ObdProt.tCodes.values() : null,
                ObdProt.VidPvs != null ? ObdProt.VidPvs.values() : null,
                ObdProt.PidPvs != null ? ObdProt.PidPvs.values() : null,
                labels);

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null)
        {
            clipboard.setPrimaryClip(ClipData.newPlainText("AndrOBD2 Diagnostic Report", report));
            Toast.makeText(this, R.string.dtc_report_copied_toast, Toast.LENGTH_LONG).show();
        }
    }

    private void saveRecordedCsvInBackground(final String exportText)
    {
        if (exportText == null || exportText.isEmpty()) return;
        executor.execute(() -> {
            try
            {
                String dirPath = FileHelper.getPath(MainActivity.this) + File.separator + "csv";
                File csvFile = new File(dirPath, "live_" + FileHelper.getFileName() + ".csv");
                LiveDataRecorder.writeUtf8CsvFile(csvFile, exportText);
                liveDataRecorder.setLastSavedFilePath(csvFile.getAbsolutePath());
            } catch (Exception e)
            {
                log.log(Level.WARNING, "Failed to save live CSV backup", e);
            }
        });
    }

    private void copyLiveRecordingToClipboard()
    {
        if (!liveDataRecorder.hasSamples())
        {
            return;
        }

        String fullLog = liveDataRecorder.buildFormattedLog(buildRecorderExportLabels());
        if (fullLog.isEmpty())
        {
            return;
        }

        String clipPayload = prepareClipboardPayload(fullLog, liveDataRecorder.getLastSavedFilePath());
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null)
        {
            return;
        }

        try
        {
            clipboard.setPrimaryClip(ClipData.newPlainText("AndrOBD2 Live Log", clipPayload));
        } catch (RuntimeException ex)
        {
            // Fallback for devices with strict Binder transaction limits
            int fallbackLimit = Math.min(clipPayload.length(), 150_000);
            int lastNl = clipPayload.lastIndexOf('\n', fallbackLimit);
            String safeSlice = lastNl > 0 ? clipPayload.substring(0, lastNl + 1) : clipPayload.substring(0, fallbackLimit);
            clipboard.setPrimaryClip(ClipData.newPlainText("AndrOBD2 Live Log", safeSlice));
        }

        Toast.makeText(this,
                getString(R.string.live_rec_copied_toast_fmt,
                        liveDataRecorder.getSampleCount(),
                        liveDataRecorder.getParameterCount()),
                Toast.LENGTH_LONG).show();
    }

    static String prepareClipboardPayload(String fullLog, String savedFilePath)
    {
        if (fullLog == null) return "";
        if (fullLog.length() <= MAX_CLIPBOARD_CHARS)
        {
            return fullLog;
        }
        int cut = fullLog.lastIndexOf('\n', MAX_CLIPBOARD_CHARS - 256);
        if (cut <= 0) cut = MAX_CLIPBOARD_CHARS - 256;
        StringBuilder sb = new StringBuilder(cut + 200);
        sb.append(fullLog, 0, cut).append('\n');
        if (savedFilePath != null && !savedFilePath.isEmpty())
        {
            sb.append("# CSV: ").append(savedFilePath).append('\n');
        }
        return sb.toString();
    }

    private void shareLiveRecording()
    {
        if (!liveDataRecorder.hasSamples()) return;

        String fullLog = liveDataRecorder.buildFormattedLog(buildRecorderExportLabels());
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        String savedPath = liveDataRecorder.getLastSavedFilePath();
        if (savedPath != null && new File(savedPath).exists())
        {
            try
            {
                Uri uri = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".provider",
                        new File(savedPath));
                sendIntent.setType("text/csv");
                sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
                sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored)
            {
                sendIntent.setType("text/plain");
            }
        } else
        {
            sendIntent.setType("text/plain");
        }
        sendIntent.putExtra(Intent.EXTRA_TEXT, prepareClipboardPayload(fullLog, savedPath));
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share)));
    }

    private void showLiveRecordingFinishedDialog()
    {
        String durationStr = LiveDataRecorder.formatDurationMmSs(liveDataRecorder.getDurationSeconds());
        String summaryMsg = getString(
                R.string.live_rec_done_summary_fmt,
                durationStr,
                liveDataRecorder.getSampleCount(),
                liveDataRecorder.getParameterCount(),
                liveDataRecorder.buildShortPreview(6));

        new AlertDialog.Builder(this)
                .setTitle(R.string.live_rec_done_title)
                .setMessage(summaryMsg)
                .setPositiveButton(R.string.live_rec_copy_all, (dialog, which) -> copyLiveRecordingToClipboard())
                .setNeutralButton(R.string.share, (dialog, which) -> shareLiveRecording())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshLiveRecorderUi()
    {
        if (mListView == null) return;

        View btnRecord = mListView.findViewById(R.id.btn_live_record);
        ImageView ivRecordIcon = mListView.findViewById(R.id.iv_live_record_icon);
        TextView tvRecordLabel = mListView.findViewById(R.id.tv_live_record_label);

        View btnMark = mListView.findViewById(R.id.btn_live_mark);
        TextView tvMarkLabel = mListView.findViewById(R.id.tv_live_mark_label);

        View btnCopy = mListView.findViewById(R.id.btn_copy_live_record);
        TextView tvCopyLabel = mListView.findViewById(R.id.tv_copy_record_label);

        boolean isRec = liveDataRecorder.isRecording();
        boolean hasData = liveDataRecorder.hasSamples();
        int sampleCount = liveDataRecorder.getSampleCount();
        int markerCount = liveDataRecorder.getMarkerCount();

        if (btnMark != null)
        {
            btnMark.setVisibility(isRec ? View.VISIBLE : View.GONE);
            if (tvMarkLabel != null)
            {
                tvMarkLabel.setText(markerCount > 0
                        ? getString(R.string.live_rec_mark_fmt, markerCount)
                        : getString(R.string.live_rec_mark));
            }
        }

        if (btnRecord != null && ivRecordIcon != null && tvRecordLabel != null)
        {
            if (isRec)
            {
                btnRecord.setBackgroundResource(R.drawable.bg_btn_danger_soft);
                ivRecordIcon.setImageResource(R.drawable.ic_stop_square);
                ivRecordIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_danger));
                tvRecordLabel.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
                String mmSs = LiveDataRecorder.formatDurationMmSs(liveDataRecorder.getDurationSeconds());
                tvRecordLabel.setText(getString(R.string.live_rec_stop_fmt, mmSs, sampleCount));
            } else
            {
                btnRecord.setBackgroundResource(R.drawable.bg_btn_secondary);
                ivRecordIcon.setImageResource(R.drawable.ic_record_dot);
                ivRecordIcon.clearColorFilter();
                tvRecordLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                tvRecordLabel.setText(R.string.live_rec_start);
            }
        }

        if (btnCopy != null && tvCopyLabel != null)
        {
            if (hasData && !isRec)
            {
                btnCopy.setVisibility(View.VISIBLE);
                tvCopyLabel.setText(getString(R.string.live_rec_copy_fmt, sampleCount));
            } else
            {
                btnCopy.setVisibility(View.GONE);
            }
        }
    }

    private void syncKeepScreenOnFlag()
    {
        boolean keepOn = liveDataRecorder.isRecording()
                || (prefs != null && prefs.getBoolean(KEEP_SCREEN_ON, false));
        if (keepOn)
        {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else
        {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onStart()
    {
        super.onStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSIONS)
        {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults)
            {
                if (result != PackageManager.PERMISSION_GRANTED)
                {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted)
            {
                if (pendingConnectAfterPermission)
                {
                    pendingConnectAfterPermission = false;
                    startRealConnection();
                } else
                {
                    initSelectedMode();
                }
            } else
            {
                pendingConnectAfterPermission = false;
                Toast.makeText(this, R.string.bt_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        if (updateTimer != null)
        {
            updateTimer.cancel();
        }
        liveDataRecorder.setOnTickListener(null);
        ModernUiUtils.optimizeListView(getListView());
    }

    @Override
    protected void onResume()
    {
        SettingsActivity.applyLocale(this);

        liveDataRecorder.setOnTickListener(this::refreshLiveRecorderUi);
        syncKeepScreenOnFlag();

        updateTimer = new Timer();
        final TimerTask updateTask = new TimerTask()
        {
            @Override
            public void run()
            {
                Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_UPDATE_VIEW);
                mHandler.sendMessage(msg);
            }
        };
        updateTimer.schedule(updateTask, 0, DISPLAY_UPDATE_TIME);
        applyActivePresetPolling();
        refreshAllUi();

        super.onResume();
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy()
    {
        cancelAutoReconnect();
        setAutoHider(false);
        liveDataRecorder.setOnTickListener(null);
        if (isFinishing() && liveDataRecorder.isRecording())
        {
            liveDataRecorder.stop();
        }

        try
        {
            CommService.elm.goToSleep();
            Thread.sleep(100, 0);
        } catch (InterruptedException e)
        {
            log.log(Level.FINER, e.getLocalizedMessage());
        }

        removeDataListeners();
        CommService.elm.removePropertyChangeListener(this);

        stopDemoService();
        setMode(MODE.OFFLINE);

        if (mCommService != null)
        {
            mCommService.stop();
        }

        if (mBluetoothAdapter != null && !initialBtStateEnabled
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            try
            {
                mBluetoothAdapter.disable();
            } catch (SecurityException ignored)
            {
            }
        }

        log.info(String.format(Locale.US, "%s %s finished",
                getString(R.string.app_name),
                getString(R.string.app_version)));

        if (logFileHandler != null) logFileHandler.close();
        Logger.getLogger("").removeHandler(logFileHandler);

        executor.shutdown();
        ModernUiUtils.cleanupViewHierarchy(findViewById(android.R.id.content));

        super.onDestroy();
    }

    @Override
    public void setContentView(int layoutResID)
    {
        ViewGroup contentRoot = findViewById(android.R.id.content);
        setContentView(getLayoutInflater().inflate(layoutResID, contentRoot, false));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void setContentView(View view)
    {
        super.setContentView(view);
        ModernUiUtils.applyEdgeToEdgeInsets(this);
        applyOrientationDensityAdjustments(getResources().getConfiguration());
        if (getListView() != null)
        {
            getListView().setOnTouchListener(toolbarAutoHider);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        ModernUiUtils.applyEdgeToEdgeInsets(this);
        applyOrientationDensityAdjustments(newConfig);
    }

    private void applyOrientationDensityAdjustments(android.content.res.Configuration config)
    {
        if (mListView == null || config == null) return;
        View controlBar = mListView.findViewById(R.id.data_header_panel);
        if (controlBar != null)
        {
            float d = getResources().getDisplayMetrics().density;
            boolean shortScreen = config.screenHeightDp > 0 && config.screenHeightDp < 440;
            int hPad = Math.round(12 * d);
            int topPad = Math.round((shortScreen ? 5 : 8) * d);
            int botPad = Math.round((shortScreen ? 4 : 8) * d);
            controlBar.setPadding(hPad, topPad, hPad, botPad);
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed()
    {
        if (getListAdapter() == pluginHandler)
        {
            setObdService(obdService, null);
        } else
        {
            if (CommService.elm.getService() != ObdProt.OBD_SVC_NONE
                    || obdService != ObdProt.OBD_SVC_NONE)
            {
                if (dataViewMode != DATA_VIEW_MODE.LIST)
                {
                    setDataViewMode(DATA_VIEW_MODE.LIST);
                    setObdService(ObdProt.OBD_SVC_DATA, null);
                } else
                {
                    setObdService(ObdProt.OBD_SVC_NONE, null);
                }
            } else
            {
                if (lastBackPressTime < System.currentTimeMillis() - EXIT_TIMEOUT)
                {
                    exitToast = Toast.makeText(this, R.string.back_again_to_exit, Toast.LENGTH_SHORT);
                    exitToast.show();
                    lastBackPressTime = System.currentTimeMillis();
                } else
                {
                    if (exitToast != null)
                    {
                        exitToast.cancel();
                    }
                    super.onBackPressed();
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.main, menu);
        getMenuInflater().inflate(R.menu.obd_services,
                Objects.requireNonNull(menu.findItem(R.id.obd_services).getSubMenu()));
        MainActivity.menu = menu;
        setConversionSystem(EcuDataItem.cnvSystem);
        updateMenuVisibility();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();

        if (id == android.R.id.home)
        {
            onBackPressed();
            return true;
        }

        if (id == R.id.day_night_mode)
        {
            boolean currentlyDark = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            prefs.edit().putString(THEME_MODE, currentlyDark ? "light" : "dark").apply();
            return true;
        }

        // Separate Real Connection vs Demo Mode
        if (id == R.id.secure_connect_scan)
        {
            startRealConnection();
            return true;
        }

        if (id == R.id.action_demo)
        {
            startDemoMode();
            return true;
        }

        if (id == R.id.disconnect)
        {
            disconnectSession();
            return true;
        }

        if (id == R.id.reset_preselections)
        {
            clearPreselections();
            recreate();
            return true;
        }

        if (id == R.id.settings)
        {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivityForResult(settingsIntent, REQUEST_SETTINGS);
            return true;
        }

        if (id == R.id.plugin_manager)
        {
            setManagerView();
            final ActionBar ab = getSupportActionBar();
            if (ab != null)
            {
                ab.setDisplayHomeAsUpEnabled(true);
                ab.setTitle(R.string.plugin_manager);
            }
            return true;
        }

        if (id == R.id.copy_live_recording)
        {
            copyLiveRecordingToClipboard();
            return true;
        }

        if (id == R.id.save)
        {
            fileHelper.saveDataThreaded();
            return true;
        }

        if (id == R.id.load)
        {
            setMode(MODE.FILE);
            selectFileToLoad();
            return true;
        }

        if (id == R.id.service_none)
        {
            setObdService(ObdProt.OBD_SVC_NONE, item.getTitle());
            return true;
        }

        if (id == R.id.service_data)
        {
            setObdService(ObdProt.OBD_SVC_DATA, item.getTitle());
            return true;
        }

        if (id == R.id.service_vid_data)
        {
            setObdService(ObdProt.OBD_SVC_VEH_INFO, item.getTitle());
            return true;
        }

        if (id == R.id.service_freezeframes)
        {
            setObdService(ObdProt.OBD_SVC_FREEZEFRAME, item.getTitle());
            return true;
        }

        if (id == R.id.service_testcontrol)
        {
            setObdService(ObdProt.OBD_SVC_CTRL_MODE, item.getTitle());
            return true;
        }

        if (id == R.id.service_codes)
        {
            setObdService(ObdProt.OBD_SVC_READ_CODES, item.getTitle());
            return true;
        }

        if (id == R.id.service_clearcodes)
        {
            clearObdFaultCodes();
            setObdService(ObdProt.OBD_SVC_READ_CODES, item.getTitle());
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked)
    {
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu)
    {
        MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.context_graph, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu)
    {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item)
    {
        int id = item.getItemId();

        if (id == R.id.chart_selected)
        {
            setDataViewMode(DATA_VIEW_MODE.CHART);
            return true;
        }
        if (id == R.id.hud_selected)
        {
            setDataViewMode(DATA_VIEW_MODE.HEADUP);
            return true;
        }
        if (id == R.id.dashboard_selected)
        {
            setDataViewMode(DATA_VIEW_MODE.DASHBOARD);
            return true;
        }
        if (id == R.id.filter_selected)
        {
            setDataViewMode(DATA_VIEW_MODE.FILTERED);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode)
    {
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        boolean secureConnection = false;

        switch (requestCode)
        {
            case REQUEST_CONNECT_DEVICE_SECURE:
                secureConnection = true;
                // fall through
            case REQUEST_CONNECT_DEVICE_INSECURE:
                if (resultCode == Activity.RESULT_OK && data != null && data.getExtras() != null)
                {
                    String address = data.getExtras().getString(BtDeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    if (address != null)
                    {
                        prefs.edit().putString(PRESELECT.LAST_DEV_ADDRESS.toString(), address).apply();
                        if (CommService.medium == CommService.MEDIUM.BLE)
                        {
                            connectBleDevice(address, secureConnection);
                        } else
                        {
                            connectBtDevice(address, secureConnection);
                        }
                    } else
                    {
                        setMode(MODE.OFFLINE);
                    }
                } else
                {
                    setMode(MODE.OFFLINE);
                }
                break;

            case REQUEST_CONNECT_DEVICE_USB:
                if (resultCode == Activity.RESULT_OK && UsbDeviceListActivity.selectedPort != null)
                {
                    mCommService = new UsbCommService(this, mHandler);
                    mCommService.connect(UsbDeviceListActivity.selectedPort, true);
                } else
                {
                    setMode(MODE.OFFLINE);
                }
                break;

            case REQUEST_ENABLE_BT:
                boolean shouldConnect = pendingConnectAfterBtEnable;
                pendingConnectAfterBtEnable = false;
                if (resultCode == Activity.RESULT_OK)
                {
                    if (shouldConnect)
                    {
                        startRealConnection();
                    }
                } else
                {
                    setMode(MODE.OFFLINE);
                }
                break;

            case REQUEST_SELECT_FILE:
                if (resultCode == RESULT_OK && data != null && data.getData() != null)
                {
                    loadDataFromUri(data.getData());
                }
                break;

            case REQUEST_SETTINGS:
                refreshAllUi();
                break;

            case REQUEST_GRAPH_DISPLAY_DONE:
                dataViewMode = DATA_VIEW_MODE.LIST;
                applyActivePresetPolling();
                break;
        }
    }

    void loadDataFromUri(Uri uri)
    {
        log.info("Load content: " + uri);
        fileHelper.loadDataThreaded(uri, mHandler);
        setMenuItemEnable(R.id.save, false);
        setMenuItemEnable(R.id.obd_services, true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key)
    {
        if (key == null || KEEP_SCREEN_ON.equals(key))
        {
            syncKeepScreenOnFlag();
        }

        if (key == null || PREF_FULLSCREEN.equals(key))
        {
            getWindow().setFlags(prefs.getBoolean(PREF_FULLSCREEN, false)
                            ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        if (key == null || THEME_MODE.equals(key))
        {
            applyThemeMode(true);
        }

        if (key == null || SettingsActivity.KEY_COMM_MEDIUM.equals(key))
        {
            CommService.medium = resolveRealMedium(prefs);
            refreshHomeUi();
        }

        if (key == null || ELM_ADAPTIVE_TIMING.equals(key))
        {
            CommService.elm.mAdaptiveTiming.setMode(
                    ElmProt.AdaptTimingMode.valueOf(
                            prefs.getString(ELM_ADAPTIVE_TIMING,
                                    ElmProt.AdaptTimingMode.OFF.toString())));
        }

        if (key == null || ELM_RESET_ON_NRC.equals(key))
        {
            CommService.elm.setResetOnNrc(prefs.getBoolean(ELM_RESET_ON_NRC, false));
        }

        if (key == null || ELM_CUSTOM_INIT_CMDS.equals(key))
        {
            String value = prefs.getString(ELM_CUSTOM_INIT_CMDS, null);
            if (value != null && !value.isEmpty())
            {
                CommService.elm.setCustomInitCommands(value.split("\n"));
            }
        }

        if (key == null || SettingsActivity.ELM_MIN_TIMEOUT.equals(key))
        {
            CommService.elm.mAdaptiveTiming.setElmTimeoutMin(
                    getPrefsInt(SettingsActivity.ELM_MIN_TIMEOUT,
                            CommService.elm.mAdaptiveTiming.getElmTimeoutMin()));
        }

        if (key == null || MEASURE_SYSTEM.equals(key))
        {
            setConversionSystem(getPrefsInt(MEASURE_SYSTEM, EcuDataItem.SYSTEM_METRIC));
        }

        if (key == null || SettingsActivity.KEY_PROT_SELECT.equals(key))
        {
            ElmProt.setPreferredProtocol(getPrefsInt(SettingsActivity.KEY_PROT_SELECT, 0));
        }

        if (key == null || LOG_MASTER.equals(key))
        {
            setLogLevels();
        }

        if (key == null || key.startsWith("ext_file"))
        {
            loadPreferredExtensions();
        }

        if (key == null || SettingsActivity.ELM_CMD_DISABLE.equals(key))
        {
            ElmProt.disableCommands(prefs.getStringSet(SettingsActivity.ELM_CMD_DISABLE, null));
        }

        if (key == null || PREF_AUTOHIDE.equals(key) || PREF_AUTOHIDE_DELAY.equals(key))
        {
            setAutoHider(prefs.getBoolean(PREF_AUTOHIDE, false));
        }

        if (key == null || PREF_DATA_DISABLE_MAX.equals(key))
        {
            EcuDataItem.MAX_ERROR_COUNT = getPrefsInt(PREF_DATA_DISABLE_MAX, 3);
        }

        if (key != null)
        {
            updatePidColor(key);
            updatePidDisplayRange(key);
            updatePidUpdatePeriod(key);
        } else
        {
            for (String currKey : prefs.getAll().keySet())
            {
                updatePidColor(currKey);
                updatePidDisplayRange(currKey);
                updatePidUpdatePeriod(currKey);
            }
        }
    }

    private void updatePidColor(String key)
    {
        int pos = key.indexOf("/".concat(EcuDataPv.FID_COLOR));
        if (pos >= 0)
        {
            String mnemonic = key.substring(0, pos);
            EcuDataItem itm = EcuDataItems.byMnemonic.get(mnemonic);
            Integer color = prefs.getInt(key, Color.BLACK);
            if (itm != null && Color.BLACK != color)
            {
                itm.pv.put(EcuDataPv.FID_COLOR, color);
                log.info(String.format(Locale.US, "PID pref %s=#%08x", key, color));
            }
        }
    }

    private void updatePidDisplayRange(String key)
    {
        final String[] rangeFields = new String[]{
            EcuDataPv.FID_MIN,
            EcuDataPv.FID_MAX
        };
        for (String field : rangeFields)
        {
            int pos = key.indexOf("/".concat(field));
            if (pos >= 0)
            {
                Number value = prefs.getFloat(key, Float.MAX_VALUE);
                if (Float.MAX_VALUE != value.floatValue())
                {
                    String mnemonic = key.substring(0, pos);
                    EcuDataItem itm = EcuDataItems.byMnemonic.get(mnemonic);
                    if (itm != null)
                    {
                        itm.pv.put(field, value);
                        log.info(String.format(Locale.US, "PID pref %s=%f", key, value.doubleValue()));
                    }
                }
            }
        }
    }

    private void updatePidUpdatePeriod(String key)
    {
        int pos = key.indexOf("/".concat(EcuDataPv.FID_UPDT_PERIOD));
        if (pos >= 0)
        {
            long value = prefs.getLong(key, 0);
            if (0 != value)
            {
                String mnemonic = key.substring(0, pos);
                EcuDataItem itm = EcuDataItems.byMnemonic.get(mnemonic);
                if (itm != null)
                {
                    itm.updatePeriod_ms = value;
                    log.info(String.format(Locale.US, "PID pref %s=%d", key, value));
                }
            }
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id)
    {
        Intent intent;
        EcuDataPv pv;

        switch (CommService.elm.getService())
        {
            case ObdProt.OBD_SVC_DATA:
                pv = (EcuDataPv) getListAdapter().getItem(position);
                if (pv != null && pv.get(EcuDataPv.FID_VALUE) instanceof Number)
                {
                    DashBoardActivity.setAdapter(getListAdapter());
                    intent = new Intent(this, DashBoardActivity.class);
                    intent.putExtra(DashBoardActivity.POSITIONS, new int[]{position});
                    startActivity(intent);
                }
                break;

            case ObdProt.OBD_SVC_READ_CODES:
            case ObdProt.OBD_SVC_PERMACODES:
            case ObdProt.OBD_SVC_PENDINGCODES:
                try
                {
                    intent = new Intent(Intent.ACTION_WEB_SEARCH);
                    EcuCodeItem dfc = (EcuCodeItem) getListAdapter().getItem(position);
                    intent.putExtra(SearchManager.QUERY,
                            "OBD " + dfc.get(EcuCodeItem.FID_CODE));
                    startActivity(intent);
                } catch (Exception e)
                {
                    log.log(Level.SEVERE, "WebSearch DFC", e);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                }
                break;

            case ObdProt.OBD_SVC_VEH_INFO:
                pv = (EcuDataPv) getListAdapter().getItem(position);
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText(String.valueOf(pv.get(EcuDataPv.FID_DESCRIPT)),
                        String.valueOf(pv.get(EcuDataPv.FID_VALUE)));
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                break;

            case ObdProt.OBD_SVC_CTRL_MODE:
                pv = (EcuDataPv) getListAdapter().getItem(position);
                confirmObdTestControl(pv.get(EcuDataPv.FID_DESCRIPT).toString(),
                        ObdProt.OBD_SVC_CTRL_MODE,
                        pv.getAsInt(EcuDataPv.FID_PID));
                break;
        }
        return true;
    }

    @Override
    public synchronized void pvChanged(PvChangeEvent event)
    {
        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_DATA_ITEMS_CHANGED);
        if (!event.isChildEvent())
        {
            msg.obj = event;
            mHandler.sendMessage(msg);
        }
    }

    private boolean istRestoreWanted(PRESELECT preselect)
    {
        return prefs.getStringSet(PREF_USE_LAST, emptyStringSet).contains(preselect.toString());
    }

    private void checkToRestoreLastDataSelection()
    {
        if (istRestoreWanted(PRESELECT.LAST_ITEMS))
        {
            int[] lastSelectedItems =
                    toIntArray(prefs.getString(PRESELECT.LAST_ITEMS.toString(), ""));
            if (lastSelectedItems.length > 0)
            {
                if (!selectDataItems(lastSelectedItems))
                {
                    prefs.edit().remove(PRESELECT.LAST_ITEMS.toString()).apply();
                    log.warning(String.format(Locale.US, "Invalid preselection: %s",
                            Arrays.toString(lastSelectedItems)));
                }
            }
        }
    }

    private void checkToRestoreLastViewMode()
    {
        if (istRestoreWanted(PRESELECT.LAST_VIEW_MODE))
        {
            DATA_VIEW_MODE lastMode =
                    DATA_VIEW_MODE.valueOf(prefs.getString(PRESELECT.LAST_VIEW_MODE.toString(),
                            DATA_VIEW_MODE.LIST.toString()));
            setDataViewMode(lastMode);
        }
    }

    private int[] toIntArray(String input)
    {
        int[] result = {};
        int numValidEntries = 0;
        try
        {
            String beforeSplit = input.replaceAll("\\[|]|\\s", "");
            String[] split = beforeSplit.split(",");
            int[] ints = new int[split.length];
            for (String s : split)
            {
                if (!s.isEmpty())
                {
                    ints[numValidEntries++] = Integer.parseInt(s);
                }
            }
            result = Arrays.copyOf(ints, numValidEntries);
        } catch (Exception ex)
        {
            log.severe(ex.toString());
        }

        return result;
    }

    private static String describeEcuAddress(int addr)
    {
        switch (addr)
        {
            case 0x7E8:      return "ECM (11-bit CAN)";
            case 0x7E9:      return "TCM (11-bit CAN)";
            case 0x7EA:      return "SCM (11-bit CAN)";
            case 0x18DAF110: return "ECM (29-bit CAN)";
            case 0x18DAF121: return "TCM (29-bit CAN)";
            case 0x10:       return "ECM (KW2000/ISO9141)";
            case 0x12:       return "TCM (KW2000/ISO9141)";
            default:         return "";
        }
    }

    private void selectEcu(final Set<Integer> ecuAdresses)
    {
        if (ecuAdresses.size() > 1)
        {
            int preferredAddress = prefs.getInt(PRESELECT.LAST_ECU_ADDRESS.toString(), 0);
            if (istRestoreWanted(PRESELECT.LAST_ECU_ADDRESS)
                    && ecuAdresses.contains(preferredAddress))
            {
                CommService.elm.setEcuAddress(preferredAddress);
            } else
            {
                final Integer[] addrArray = ecuAdresses.toArray(new Integer[0]);
                final CharSequence[] entries = new CharSequence[addrArray.length];
                for (int i = 0; i < addrArray.length; i++)
                {
                    String desc = describeEcuAddress(addrArray[i]);
                    entries[i] = desc.isEmpty()
                            ? String.format(Locale.US, "0x%X", addrArray[i])
                            : String.format(Locale.US, "0x%X — %s", addrArray[i], desc);
                }
                dlgBuilder
                        .setTitle(R.string.select_ecu_addr)
                        .setItems(entries, new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                int address = addrArray[which];
                                CommService.elm.setEcuAddress(address);
                                prefs.edit().putInt(PRESELECT.LAST_ECU_ADDRESS.toString(), address)
                                        .apply();
                            }
                        })
                        .show();
            }
        }
    }

    public void browseClickedUrl(View view)
    {
        String url = view.getContentDescription().toString();
        startActivity(new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)));
    }

    private void unHideActionBar()
    {
        if (toolbarAutoHider != null)
        {
            toolbarAutoHider.showComponent();
        }
    }

    static String getThemeModePref(SharedPreferences prefs)
    {
        if (prefs.contains(THEME_MODE))
        {
            return prefs.getString(THEME_MODE, "system");
        }
        if (prefs.contains(NIGHT_MODE))
        {
            String migrated = prefs.getBoolean(NIGHT_MODE, false) ? "dark" : "light";
            prefs.edit().putString(THEME_MODE, migrated).remove(NIGHT_MODE).apply();
            return migrated;
        }
        return "system";
    }

    static int themeModeToNightMode(String themeMode)
    {
        switch (themeMode)
        {
            case "dark":
                return AppCompatDelegate.MODE_NIGHT_YES;
            case "light":
                return AppCompatDelegate.MODE_NIGHT_NO;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    private void applyThemeMode(boolean recreateIfChanged)
    {
        int wanted = themeModeToNightMode(getThemeModePref(prefs));
        boolean changed = AppCompatDelegate.getDefaultNightMode() != wanted;
        AppCompatDelegate.setDefaultNightMode(wanted);
        if (changed && recreateIfChanged)
        {
            recreate();
        }
    }

    private void setNumCodes(int newNumCodes)
    {
        lastNumCodes = newNumCodes;
        setMenuItemEnable(R.id.service_freezeframes, (newNumCodes != 0));
        refreshDataHeaderUi();
    }

    private void setMenuItemEnable(int id, boolean enabled)
    {
        if (menu != null)
        {
            MenuItem item = menu.findItem(id);
            if (item != null)
            {
                item.setEnabled(enabled);
                Drawable icon = item.getIcon();
                if (icon != null)
                {
                    icon.setAlpha(enabled ? 255 : 127);
                }
            }
        }
    }

    private void setMenuItemVisible(int id, boolean visible)
    {
        if (menu != null)
        {
            MenuItem item = menu.findItem(id);
            if (item != null)
            {
                item.setVisible(visible);
            }
        }
    }

    /**
     * Update menu visibility with clear separation between Real Connect and Demo Mode.
     */
    private void updateMenuVisibility()
    {
        if (menu == null) return;

        switch (mode)
        {
            case OFFLINE:
                setMenuItemVisible(R.id.secure_connect_scan, true);
                setMenuItemVisible(R.id.action_demo, true);
                setMenuItemVisible(R.id.disconnect, false);
                setMenuItemEnable(R.id.obd_services, false);
                setMenuItemVisible(R.id.settings, true);
                setMenuItemVisible(R.id.day_night_mode, true);
                break;

            case DEMO:
                // Allow switching straight from Demo into Real Connect, or stopping Demo
                setMenuItemVisible(R.id.secure_connect_scan, true);
                setMenuItemVisible(R.id.action_demo, false);
                setMenuItemVisible(R.id.disconnect, true);
                setMenuItemEnable(R.id.obd_services, true);
                break;

            case ONLINE:
                setMenuItemVisible(R.id.secure_connect_scan, false);
                setMenuItemVisible(R.id.action_demo, false);
                setMenuItemVisible(R.id.disconnect, true);
                setMenuItemEnable(R.id.obd_services, true);
                break;

            case FILE:
                setMenuItemVisible(R.id.secure_connect_scan, true);
                setMenuItemVisible(R.id.action_demo, true);
                setMenuItemVisible(R.id.disconnect, true);
                setMenuItemEnable(R.id.obd_services, true);
                break;
        }
        setMenuItemVisible(R.id.copy_live_recording,
                liveDataRecorder.hasSamples() && !liveDataRecorder.isRecording());
    }

    private void setAutoHider(boolean active)
    {
        if (toolbarAutoHider != null)
        {
            toolbarAutoHider.cancel();
            toolbarAutoHider = null;
        }

        if (active)
        {
            int timeout = getPrefsInt(MainActivity.PREF_AUTOHIDE_DELAY, 15);
            toolbarAutoHider = new AutoHider(this, mHandler, timeout * 1000L);
            toolbarAutoHider.start(1000);
        }
    }

    private int getPrefsInt(String key, int defaultValue)
    {
        int result = defaultValue;
        try
        {
            result = Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
        } catch (Exception ex)
        {
            log.severe(String.format(Locale.US, "Preference '%s'(%d): %s", key, result, ex));
        }
        return result;
    }

    private void setDataListeners()
    {
        ObdProt.PidPvs.addPvChangeListener(this,
                PvChangeEvent.PV_ADDED | PvChangeEvent.PV_CLEARED);
        ObdProt.VidPvs.addPvChangeListener(this,
                PvChangeEvent.PV_ADDED | PvChangeEvent.PV_CLEARED);
        ObdProt.tCodes.addPvChangeListener(this,
                PvChangeEvent.PV_ADDED | PvChangeEvent.PV_CLEARED);
        mPluginPvs.addPvChangeListener(this,
                PvChangeEvent.PV_ADDED | PvChangeEvent.PV_CLEARED);
    }

    private void removeDataListeners()
    {
        ObdProt.PidPvs.removePvChangeListener(this);
        ObdProt.VidPvs.removePvChangeListener(this);
        ObdProt.tCodes.removePvChangeListener(this);
        mPluginPvs.removePvChangeListener(this);
    }

    private MODE getMode()
    {
        return mode;
    }

    private void setMode(MODE newMode)
    {
        if (newMode != MODE.ONLINE)
        {
            cancelAutoReconnect();
        }
        if (newMode == MODE.OFFLINE || newMode == MODE.FILE)
        {
            ObdItemAdapter.pollRateTracker.reset();
        }
        if (newMode != this.mode || newMode == MODE.FILE)
        {
            if ((newMode == MODE.OFFLINE || newMode == MODE.FILE)
                    && liveDataRecorder.isRecording())
            {
                stopLiveRecording(false);
            }

            if (newMode != MODE.DEMO)
            {
                stopDemoService();
            }

            ObdItemAdapter.allowDataUpdates = (newMode != MODE.FILE);

            switch (newMode)
            {
                case OFFLINE:
                    lastNumCodes = 0;
                    setStatus(R.string.status_offline);
                    break;

                case ONLINE:
                    setStatus(R.string.status_online);
                    break;

                case DEMO:
                    startDemoService();
                    break;

                case FILE:
                    setStatus(R.string.saved_data);
                    break;
            }

            this.mode = newMode;
            updateMenuVisibility();
            refreshAllUi();
        }
    }

    private void setConversionSystem(int cnvId)
    {
        log.info("Conversion: " + getResources().getStringArray(R.array.measure_options)[cnvId]);
        if (EcuDataItem.cnvSystem != cnvId)
        {
            EcuDataItem.cnvSystem = cnvId;
        }
    }

    private void setupLoggers()
    {
        setLogLevels();
        if (Level.OFF.equals(rootLogger.getLevel())) return;

        String logFileName = FileHelper.getPath(this).concat(File.separator).concat("log");
        try
        {
            //noinspection ResultOfMethodCallIgnored
            new File(logFileName).mkdirs();
            logFileHandler = new FileHandler(logFileName.concat("/AndrOBD.log.%g.txt"),
                    250 * 1024 * 1024,
                    5,
                    false);
            logFileHandler.setFormatter(new SimpleFormatter()
            {
                final String format = "%1$tF\t%1$tT.%1$tL\t%4$s\t%3$s\t%5$s%n";

                @Override
                public synchronized String format(LogRecord lr)
                {
                    return String.format(Locale.US, format,
                            new Date(lr.getMillis()),
                            lr.getSourceClassName(),
                            lr.getLoggerName(),
                            lr.getLevel().getName(),
                            lr.getMessage()
                    );
                }
            });
            rootLogger.addHandler(logFileHandler);
        } catch (IOException e)
        {
            log.log(Level.SEVERE, logFileName, e);
        }
    }

    private void setLogLevels()
    {
        Level level;
        try
        {
            level = Level.parse(prefs.getString(LOG_MASTER, "INFO"));
        } catch (Exception e)
        {
            level = Level.INFO;
        }
        MainActivity.rootLogger.setLevel(level);
    }

    private void loadPreferredExtensions()
    {
        final String convFilePath = prefs.getString(SettingsActivity.extKeys[0], null);
        final String pidFilePath = prefs.getString(SettingsActivity.extKeys[1], null);

        executor.execute(() -> {
            final StringBuilder errors = new StringBuilder();

            if (convFilePath != null)
            {
                try (InputStream inStr = getContentResolver().openInputStream(Uri.parse(convFilePath)))
                {
                    if (inStr != null)
                    {
                        log.info("Load ext. conversions: " + convFilePath);
                        EcuDataItems.cnv.loadFromStream(inStr);
                    }
                } catch (Exception e)
                {
                    log.log(Level.SEVERE, "Load ext. conversions: ", e);
                    errors.append(e.getLocalizedMessage()).append("\n");
                }
            }

            if (pidFilePath != null)
            {
                try (InputStream inStr = getContentResolver().openInputStream(Uri.parse(pidFilePath)))
                {
                    if (inStr != null)
                    {
                        log.info("Load ext. PIDs: " + pidFilePath);
                        ObdProt.dataItems.loadFromStream(inStr);
                    }
                } catch (Exception e)
                {
                    log.log(Level.SEVERE, "Load ext. PIDs: ", e);
                    errors.append(e.getLocalizedMessage()).append("\n");
                }
            }

            if (errors.length() != 0)
            {
                mHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed())
                    {
                        dlgBuilder
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .setTitle(R.string.extension_loading)
                                .setMessage(getString(R.string.check_cust_settings) + errors)
                                .show();
                    }
                });
            }
        });
    }

    private void stopDemoService()
    {
        if (getMode() == MODE.DEMO || ElmProt.runDemo)
        {
            ElmProt.runDemo = false;
            Toast.makeText(this, getString(R.string.demo_stopped), Toast.LENGTH_SHORT).show();
        }
    }

    private void startDemoService()
    {
        if (getMode() != MODE.DEMO || !ElmProt.runDemo)
        {
            mode = MODE.DEMO;
            setStatus(getString(R.string.demo));
            Toast.makeText(this, getString(R.string.demo_started), Toast.LENGTH_SHORT).show();
            updateMenuVisibility();
            Thread demoThread = new Thread(CommService.elm);
            demoThread.start();
        }
    }

    private void setStatus(int resId)
    {
        setStatus(getString(resId));
    }

    private void setStatus(CharSequence subTitle)
    {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setSubtitle(subTitle);
            unHideActionBar();
        }
    }

    private void selectFileToLoad()
    {
        File filesDir = getExternalFilesDir(null);
        File[] obdFiles = (filesDir != null)
                ? filesDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".obd"))
                : null;

        if (obdFiles == null || obdFiles.length == 0)
        {
            Toast.makeText(this, R.string.no_obd_files_found, Toast.LENGTH_SHORT).show();
            setMode(MODE.OFFLINE);
            return;
        }

        Arrays.sort(obdFiles, Comparator.comparing(File::getName));
        final String[] fileNames = new String[obdFiles.length];
        for (int i = 0; i < obdFiles.length; i++)
        {
            fileNames[i] = obdFiles[i].getName();
        }
        final File[] finalFiles = obdFiles;

        new AlertDialog.Builder(this)
                .setTitle(R.string.load)
                .setItems(fileNames, (dialog, which) -> {
                    Uri uri = FileProvider.getUriForFile(this,
                            getPackageName() + ".provider",
                            finalFiles[which]);
                    loadDataFromUri(uri);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> setMode(MODE.OFFLINE))
                .show();
    }

    private void clearPreselections()
    {
        for (PRESELECT selection : PRESELECT.values())
        {
            prefs.edit().remove(selection.toString()).apply();
        }
    }

    private void connectBtDevice(String address, boolean secure)
    {
        lastConnectAddress = address;
        lastConnectSecure = secure;
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        mCommService = new BtCommService(this, mHandler);
        mCommService.connect(device, secure);
    }

    /**
     * Connect to BLE ELM327 adapter across all supported Android versions (API 23+)
     * for both public and random BLE MAC addresses.
     */
    private void connectBleDevice(String address, boolean secure)
    {
        lastConnectAddress = address;
        lastConnectSecure = secure;
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        mCommService = new BleCommService(this, mHandler);
        mCommService.connect(device, secure);
    }

    private void connectNetworkDevice(String address, int port)
    {
        lastConnectAddress = address;
        lastConnectPort = port;
        mCommService = new NetworkCommService(this, mHandler);
        ((NetworkCommService) mCommService).connect(address, port);
    }

    private void setObdService(int newObdService, CharSequence menuTitle)
    {
        obdService = newObdService;
        ignoreNrcs = false;

        if (newObdService == ObdProt.OBD_SVC_NONE)
        {
            CommService.elm.setService(newObdService, false);
            setContentView(R.layout.startup_layout);
            bindStartupLayout();
            currDataAdapter = mVidAdapter;
            setListAdapter(currDataAdapter);
            final ActionBar ab = getSupportActionBar();
            if (ab != null)
            {
                ab.setDisplayHomeAsUpEnabled(false);
                ab.setTitle(getString(R.string.app_name));
            }
            return;
        }

        setContentView(mListView);
        getListView().setOnItemLongClickListener(this);
        getListView().setMultiChoiceModeListener(this);
        getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        final ActionBar ab = getSupportActionBar();
        if (ab != null)
        {
            ab.setDisplayHomeAsUpEnabled(true);
            if (menuTitle != null)
            {
                ab.setTitle(menuTitle);
            }
        }

        CommService.elm.setService(newObdService,
                (getMode() != MODE.FILE && getMode() != MODE.OFFLINE));

        Spinner ff_selector = mListView.findViewById(R.id.ff_selector);
        if (ff_selector != null)
        {
            ff_selector.setOnItemSelectedListener(ff_selected);
            ff_selector.setAdapter(mDfcAdapter);
            ff_selector.setVisibility(
                    newObdService == ObdProt.OBD_SVC_FREEZEFRAME ? View.VISIBLE : View.GONE);
        }

        switch (newObdService)
        {
            case ObdProt.OBD_SVC_DATA:
                getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
                currDataAdapter = mPidAdapter;
                break;

            case ObdProt.OBD_SVC_FREEZEFRAME:
                currDataAdapter = mPidAdapter;
                break;

            case ObdProt.OBD_SVC_PENDINGCODES:
            case ObdProt.OBD_SVC_PERMACODES:
            case ObdProt.OBD_SVC_READ_CODES:
                ignoreNrcs = true;
                currDataAdapter = mDfcAdapter;
                break;

            case ObdProt.OBD_SVC_CTRL_MODE:
                currDataAdapter = mTidAdapter;
                break;

            case ObdProt.OBD_SVC_VEH_INFO:
            default:
                currDataAdapter = mVidAdapter;
                break;
        }

        setFiltered(false);
        setListAdapter(currDataAdapter);

        refreshDataHeaderUi();
        refreshDataListStats();

        if (newObdService > ObdProt.OBD_SVC_NONE)
        {
            prefs.edit().putInt(PRESELECT.LAST_SERVICE.toString(), newObdService).apply();
        }
    }

    private void setFiltered(boolean filtered)
    {
        if (filtered)
        {
            TreeSet<Integer> selPids = new TreeSet<>();
            int[] selectedPositions = getSelectedPositions();
            for (int pos : selectedPositions)
            {
                EcuDataPv pv = (EcuDataPv) currDataAdapter.getItem(pos);
                selPids.add(pv != null ? pv.getAsInt(EcuDataPv.FID_PID) : 0);
            }
            currDataAdapter.filterPositions(selectedPositions);

            if (currDataAdapter == mPidAdapter)
            {
                ObdProt.resetFixedPid();
                setFixedPids(selPids);
            }
        } else
        {
            if (currDataAdapter == mPidAdapter)
                ObdProt.resetFixedPid();

            if (currDataAdapter == mPidAdapter)
            {
                currDataAdapter.setPvList(ObdProt.PidPvs);
                applyActivePresetPolling();
            } else if (currDataAdapter == mVidAdapter)
            {
                currDataAdapter.setPvList(ObdProt.VidPvs);
            } else if (currDataAdapter == mDfcAdapter)
            {
                currDataAdapter.setPvList(ObdProt.tCodes);
            } else if (currDataAdapter == mPluginDataAdapter)
            {
                currDataAdapter.setPvList(mPluginPvs);
            }
        }
    }

    private int[] getSelectedPositions()
    {
        int[] selectedPositions;
        final SparseBooleanArray checkedItems = getListView().getCheckedItemPositions();
        int checkedItemsCount = getListView().getCheckedItemCount();
        selectedPositions = new int[checkedItemsCount];
        if (checkedItemsCount > 0)
        {
            int j = 0;
            for (int i = 0; i < checkedItems.size(); i++)
            {
                if (checkedItems.valueAt(i))
                {
                    selectedPositions[j++] = checkedItems.keyAt(i);
                }
            }
            selectedPositions = Arrays.copyOf(selectedPositions, j);
        }
        String strPreselect = Arrays.toString(selectedPositions);
        log.fine("Preselection: '" + strPreselect + "'");
        prefs.edit().putString(PRESELECT.LAST_ITEMS.toString(), strPreselect).apply();
        return selectedPositions;
    }

    private boolean selectDataItems(int[] positions)
    {
        Arrays.sort(positions);
        int max = positions.length > 0 ? positions[positions.length - 1] : 0;
        int count = getListAdapter().getCount();
        boolean positionsValid = (max < count);
        if (positionsValid)
        {
            for (int i : positions)
            {
                getListView().setItemChecked(i, true);
            }
        }
        return positionsValid;
    }

    @SuppressLint("StringFormatInvalid")
    private void onConnect()
    {
        boolean wasAutoReconnecting = autoReconnecting;
        cancelAutoReconnect();
        hadEstablishedConnection = true;
        userRequestedDisconnect = false;
        if (wasAutoReconnecting && obdService == ObdProt.OBD_SVC_NONE)
        {
            obdService = serviceBeforeReconnect;
        }
        stopDemoService();
        mode = MODE.ONLINE;
        updateMenuVisibility();
        refreshAllUi();
        setMenuItemEnable(R.id.obd_services, true);
        setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
        CommService.elm.reset();
    }

    private void onDisconnect()
    {
        if (mode == MODE.ONLINE
                && !userRequestedDisconnect
                && hadEstablishedConnection
                && autoReconnectAttempt < MAX_AUTO_RECONNECT_ATTEMPTS)
        {
            if (!autoReconnecting)
            {
                autoReconnecting = true;
                serviceBeforeReconnect = (obdService != ObdProt.OBD_SVC_NONE)
                        ? obdService
                        : ObdProt.OBD_SVC_DATA;
                Toast.makeText(this,
                        R.string.reconnecting_keep_log_toast,
                        Toast.LENGTH_SHORT).show();
            }
            long delayMs = AUTO_RECONNECT_DELAYS_MS[
                    Math.min(autoReconnectAttempt, AUTO_RECONNECT_DELAYS_MS.length - 1)];
            autoReconnectAttempt++;
            setStatus(getString(R.string.status_reconnecting_fmt,
                    autoReconnectAttempt, MAX_AUTO_RECONNECT_ATTEMPTS));
            refreshDataHeaderUi();
            refreshTelemetryBadges();
            mHandler.removeCallbacks(autoReconnectRunnable);
            mHandler.postDelayed(autoReconnectRunnable, delayMs);
            return;
        }

        cancelAutoReconnect();
        hadEstablishedConnection = false;
        setMode(MODE.OFFLINE);
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt)
    {
        if (ElmProt.PROP_STATUS.equals(evt.getPropertyName()))
        {
            Message msg = mHandler.obtainMessage(MESSAGE_OBD_STATE_CHANGED);
            msg.obj = evt;
            mHandler.sendMessage(msg);
        } else if (ElmProt.PROP_NUM_CODES.equals(evt.getPropertyName()))
        {
            Message msg = mHandler.obtainMessage(MESSAGE_OBD_NUMCODES);
            msg.obj = evt;
            mHandler.sendMessage(msg);
        } else if (ElmProt.PROP_ECU_ADDRESS.equals(evt.getPropertyName()))
        {
            Message msg = mHandler.obtainMessage(MESSAGE_OBD_ECUS);
            msg.obj = evt;
            mHandler.sendMessage(msg);
        } else if (ObdProt.PROP_NRC.equals(evt.getPropertyName()))
        {
            Message msg = mHandler.obtainMessage(MESSAGE_OBD_NRC);
            msg.obj = evt;
            mHandler.sendMessage(msg);
        }
    }

    private void clearObdFaultCodes()
    {
        dlgBuilder
                .setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(R.string.obd_clearcodes)
                .setMessage(R.string.obd_clear_info)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    CommService.elm.setService(ObdProt.OBD_SVC_CLEAR_CODES);
                    CommService.elm.setService(ObdProt.OBD_SVC_READ_CODES);
                })
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void confirmObdTestControl(String testControlName, int service, int tid)
    {
        dlgBuilder
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(testControlName)
                .setMessage(R.string.obd_test_confirm)
                .setPositiveButton(android.R.string.yes,
                        (dialog, which) -> runObdTestControl(testControlName, service, tid))
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void runObdTestControl(String testControlName, int service, int tid)
    {
        char[] emptyBuffer = {};
        CommService.elm.writeTelegram(emptyBuffer, service, tid);

        dlgBuilder
                .setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(testControlName)
                .setMessage(R.string.obd_test_progress)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> { })
                .setNegativeButton(null, null)
                .show();
    }

    private void setDataViewMode(DATA_VIEW_MODE dataViewMode)
    {
        if (dataViewMode != this.dataViewMode)
        {
            log.info(String.format(Locale.US, "Set view mode: %s -> %s", this.dataViewMode, dataViewMode));

            switch (dataViewMode)
            {
                case LIST:
                    setFiltered(false);
                    getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
                    this.dataViewMode = dataViewMode;
                    break;

                case FILTERED:
                    if (getListView().getCheckedItemCount() > 0)
                    {
                        setFiltered(true);
                        getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                        this.dataViewMode = dataViewMode;
                    }
                    break;

                case HEADUP:
                case DASHBOARD:
                    if (getListView().getCheckedItemCount() > 0)
                    {
                        DashBoardActivity.setAdapter(getListAdapter());
                        Intent intent = new Intent(this, DashBoardActivity.class);
                        intent.putExtra(DashBoardActivity.POSITIONS, getSelectedPositions());
                        intent.putExtra(DashBoardActivity.RES_ID,
                                dataViewMode == DATA_VIEW_MODE.DASHBOARD
                                        ? R.layout.dashboard
                                        : R.layout.head_up);
                        startActivityForResult(intent, REQUEST_GRAPH_DISPLAY_DONE);
                        this.dataViewMode = dataViewMode;
                    }
                    break;

                case CHART:
                    if (getListView().getCheckedItemCount() > 0)
                    {
                        ChartActivity.setAdapter(getListAdapter());
                        Intent intent = new Intent(this, ChartActivity.class);
                        intent.putExtra(ChartActivity.POSITIONS, getSelectedPositions());
                        intent.putExtra(ChartActivity.LIVE_UPDATE,
                                Set.of(MODE.ONLINE, MODE.DEMO).contains(getMode()));
                        startActivityForResult(intent, REQUEST_GRAPH_DISPLAY_DONE);
                        this.dataViewMode = dataViewMode;
                    }
                    break;
            }

            if (dataViewMode != DATA_VIEW_MODE.LIST)
            {
                prefs.edit().putString(PRESELECT.LAST_VIEW_MODE.toString(), dataViewMode.toString())
                        .apply();
            }
        }
    }

    @Override
    public void onDataListUpdate(String csvString)
    {
        log.log(Level.FINE, "PluginDataList: " + csvString);
        synchronized (mPluginPvs)
        {
            for (String csvLine : csvString.split("\n"))
            {
                String[] fields = csvLine.split(";");
                if (fields.length >= Plugin.CsvField.values().length)
                {
                    PluginDataPv pv = (PluginDataPv) mPluginPvs.get(fields[Plugin.CsvField.MNEMONIC.ordinal()]);
                    if (pv == null)
                    {
                        pv = new PluginDataPv();
                    }
                    for (Plugin.CsvField fld : Plugin.CsvField.values())
                    {
                        try
                        {
                            Double value = Double.valueOf(fields[fld.ordinal()]);
                            pv.put(csvFidMap[fld.ordinal()], value);
                        } catch (Exception ex)
                        {
                            pv.put(csvFidMap[fld.ordinal()], fields[fld.ordinal()]);
                        }
                    }
                    mPluginPvs.put(pv.getKeyValue(), pv);
                }
            }
        }
    }

    @Override
    public void onDataUpdate(String key, String value)
    {
        log.log(Level.FINE, "PluginData: " + key + "=" + value);
        synchronized (mPluginPvs)
        {
            ProcessVar pv = (ProcessVar) mPluginPvs.get(key);
            if (pv != null)
            {
                try
                {
                    Double numVal = Double.valueOf(value);
                    pv.put(EcuDataPv.FIELDS[EcuDataPv.FID_VALUE], numVal);
                } catch (Exception ex)
                {
                    pv.put(EcuDataPv.FIELDS[EcuDataPv.FID_VALUE], value);
                }
            }
        }
    }

    public enum MODE
    {
        OFFLINE,
        ONLINE,
        DEMO,
        FILE,
    }

    public enum DATA_VIEW_MODE
    {
        LIST,
        FILTERED,
        DASHBOARD,
        HEADUP,
        CHART,
    }

    public enum PRESELECT
    {
        LAST_DEV_ADDRESS,
        LAST_ECU_ADDRESS,
        LAST_SERVICE,
        LAST_ITEMS,
        LAST_VIEW_MODE,
    }
}
