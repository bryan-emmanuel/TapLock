package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
//import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Toast;

public class RemoteAuthClientUI extends ListActivity implements OnClickListener, ServiceConnection {
	private static final String TAG = "RemoteAuthClientUI";
	private Button mBtn_add;
	//	private CheckBox mChk_sec;
	private ProgressDialog mProgressDialog;
	private AlertDialog mDialog;
	private String[] mDevices;
	private String[] mPairedDevices = new String[0];
	private String[] mUnpairedDevices = new String[0];
	private static final int REMOVE_ID = Menu.FIRST;
	private String mDevice;
	private String mPendingTagEvent;

	public static final int STATE_UNLOCK = 0;
	public static final int STATE_LOCK = 1;
	public static final int STATE_TOGGLE = 2;
	private static final int STATE_TAG = 3;
	private static final int STATE_REMOVE = 4;
	private static final int STATE_PASSPHRASE = 5;

	public static final int DEVICE_NAME = 0;
	public static final int DEVICE_PASSPHRASE = 1;
	public static final int DEVICE_ADDRESS = 2;

	private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

	// NFC
	private NfcAdapter mNfcAdapter = null;
	private boolean mInWriteMode = false;

	private IRemoteAuthClientService mServiceInterface;
	private IRemoteAuthClientUI.Stub mUIInterface = new IRemoteAuthClientUI.Stub() {

		@Override
		public void setMessage(String message) throws RemoteException {
			Log.d(TAG, message);
			Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
		}

		@Override
		public void setUnpairedDevice(String device) throws RemoteException {
			// new unpaired device
			String[] newDevices = new String[mUnpairedDevices.length + 1];
			for (int i = 0, l = mUnpairedDevices.length; i < l; i++) {
				newDevices[i] = mUnpairedDevices[i];
			}
			newDevices[mUnpairedDevices.length] = device;
			mUnpairedDevices = newDevices;
		}

		@Override
		public void setDiscoveryFinished() throws RemoteException {
			if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
				mProgressDialog.cancel();
			}
			if (mUnpairedDevices.length > 0) {
				mDialog = new AlertDialog.Builder(RemoteAuthClientUI.this)
				.setItems(mUnpairedDevices, new DialogInterface.OnClickListener() {					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						addNewDevice(mUnpairedDevices[which]);
					}
				})
				.create();
				mDialog.show();
			} else {
				Toast.makeText(getApplicationContext(), "no devices discovered", Toast.LENGTH_LONG).show();
			}
		}

	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		registerForContextMenu(getListView());
		mBtn_add = ((Button) findViewById(R.id.btn_add));
		mBtn_add.setOnClickListener(this);
		//		mChk_sec = ((CheckBox) findViewById(R.id.chk_sec));
		//NFC
		mNfcAdapter = NfcAdapter.getDefaultAdapter(getApplicationContext());

		Intent intent = getIntent();
		if (intent != null) {
			Bundle extras = intent.getExtras();
			if (extras != null) {
				mAppWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
				Intent resultValue = new Intent();
				resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
				setResult(RESULT_OK, resultValue);
			}
		}
	}

	@Override 
	public void onNewIntent(Intent intent) {
		// handle NFC intents
		Log.d(TAG, "onNewIntent");
		if (intent != null) {
			String action = intent.getAction();
			Log.d(TAG, "action: " + action);
			Log.d(TAG, "mDevice: " + mDevice);
			Log.d(TAG, "mInWriteMode: " + mInWriteMode);
			if (mInWriteMode && action.equals(NfcAdapter.ACTION_TAG_DISCOVERED)) {
				Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
				Log.d(TAG, (tag != null ? "has tag" : "tag is null"));
				if ((tag != null) && (mDevice != null)) {
					write(tag);
					mNfcAdapter.disableForegroundDispatch(this);
					mInWriteMode = false;
				}
			} else if (action.equals(NfcAdapter.ACTION_NDEF_DISCOVERED) && intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) {
				Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
				NdefMessage message;
				if (rawMsgs != null) {
					// process the first message
					message = (NdefMessage) rawMsgs[0];
					// process the first record
					NdefRecord record = message.getRecords()[0];
					if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN) {
						try {
							byte[] payload = record.getPayload();
							/*
							 * payload[0] contains the "Status Byte Encodings" field, per the
							 * NFC Forum "Text Record Type Definition" section 3.2.1.
							 *
							 * bit7 is the Text Encoding Field.
							 *
							 * if (Bit_7 == 0): The text is encoded in UTF-8 if (Bit_7 == 1):
							 * The text is encoded in UTF16
							 *
							 * Bit_6 is reserved for future use and must be set to zero.
							 *
							 * Bits 5 to 0 are the length of the IANA language code.
							 */
							String textEncoding = ((payload[0] & 0200) == 0) ? "UTF-8" : "UTF-16";
							int languageCodeLength = payload[0] & 0077;
							mPendingTagEvent = new String(payload, languageCodeLength + 1, payload.length - languageCodeLength - 1, textEncoding);
						} catch (UnsupportedEncodingException e) {
							// should never happen unless we get a malformed tag.
							Log.e(TAG, e.toString());
						}
					}
				}
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		// start the service before binding so that the service stays around for faster future connections
		startService(new Intent(this, RemoteAuthClientService.class));
		bindService(new Intent(this, RemoteAuthClientService.class), this, BIND_AUTO_CREATE);
		final SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
		Set<String> devices = sp.getStringSet(getString(R.string.key_devices), null);
		if (devices != null) {
			mDevices = new String[devices.size()];
			int d = 0;
			Iterator<String> iter = devices.iterator();
			while (iter.hasNext()) {
				mDevices[d++] = iter.next();
			}
		} else {
			mDevices = new String[0];
		}
		setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mDevices));

		// check if configuring a widget
		if (mAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
			mDialog = new AlertDialog.Builder(this)
			.setTitle("Select device for widget")
			.setItems(mDevices, new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface dialog, int which) {
					String[] deviceParts = parseDeviceString(mDevices[which]);
					String name = deviceParts[DEVICE_NAME];
					String address = deviceParts[DEVICE_ADDRESS];
					String widgetString = name + " " + Integer.toString(mAppWidgetId) + " " + address;
					// store the widget
					Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
					if (!widgets.contains(widgetString)) {
						Log.d(TAG, "add widget: " + widgetString);
						widgets.add(widgetString);
					}
					sp.edit().putStringSet(getString(R.string.key_widgets), widgets).commit();
					dialog.cancel();
					RemoteAuthClientUI.this.finish();
					sendBroadcast(new Intent(RemoteAuthClientUI.this, RemoteAuthClientWidget.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId).putExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME, name).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, address));
					mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
				}
			})
			.create();
			mDialog.show();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (mServiceInterface != null) {
			try {
				mServiceInterface.stop();
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
			unbindService(this);
		}
		mDevice = null;
		mInWriteMode = false;
		mNfcAdapter.disableForegroundDispatch(this);
		if ((mDialog != null) && mDialog.isShowing()) {
			mDialog.cancel();
		}
		if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
			mProgressDialog.cancel();
		}
		// save devices
		Set<String> devices = new HashSet<String>();
		for (String device : mDevices) {
			devices.add(device);
		}
		SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
		SharedPreferences.Editor spe = sp.edit();
		spe.putStringSet(getString(R.string.key_devices), devices);
		spe.commit();
	}

	@Override
	protected void onListItemClick(ListView list, final View view, int position, final long id) {
		super.onListItemClick(list, view, position, id);
		mDialog = new AlertDialog.Builder(this)
		.setItems(R.array.states, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				int state = Integer.parseInt(getResources().getStringArray(R.array.state_values)[which]);
				final int deviceIdx = (int) id;
				mDevice = mDevices[deviceIdx];
				dialog.cancel();
				switch (state) {
				case STATE_UNLOCK:
				case STATE_LOCK:
				case STATE_TOGGLE:
					// attempt to connect to the device
					if (mServiceInterface != null) {
						try {
							String[] parsedDevice = parseDeviceString(mDevice);
							//							mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], Integer.toString(state), mChk_sec.isChecked(), parsedDevice[DEVICE_PASSPHRASE]);
							mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], Integer.toString(state), parsedDevice[DEVICE_PASSPHRASE]);
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					break;
				case STATE_TAG:
					// write the device to a tag
					mInWriteMode = true;
					mNfcAdapter.enableForegroundDispatch(RemoteAuthClientUI.this,
							PendingIntent.getActivity(RemoteAuthClientUI.this, 0, new Intent(RemoteAuthClientUI.this, RemoteAuthClientUI.this.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0),
							new IntentFilter[] {new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)},
							null);
					Toast.makeText(RemoteAuthClientUI.this, "Touch tag", Toast.LENGTH_LONG).show();
					break;
				case STATE_REMOVE:
					// remove device
					int p = 0;
					String[] devices = new String[mDevices.length - 1];
					for (int i = 0, l = mDevices.length; i < l; i++) {
						if (i != deviceIdx) {
							devices[p++] = mDevices[i];
						}
					}
					mDevices = devices;
					setListAdapter(new ArrayAdapter<String>(RemoteAuthClientUI.this, android.R.layout.simple_list_item_1, mDevices));
					break;
				case STATE_PASSPHRASE:
					final EditText fld_passphrase = new EditText(RemoteAuthClientUI.this);
					// parse the existing passphrase
					final String[] parsedDevice = parseDeviceString(mDevice);
					fld_passphrase.setText(parsedDevice[DEVICE_PASSPHRASE]);
					mDialog = new AlertDialog.Builder(RemoteAuthClientUI.this)
					.setTitle("set passphrase")
					.setView(fld_passphrase)
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.cancel();
							mDevice = buildDeviceString(new String[]{parsedDevice[DEVICE_NAME], fld_passphrase.getText().toString(), parsedDevice[DEVICE_ADDRESS]});
							// save the device
							mDevices[(int) deviceIdx] = mDevice;
							setListAdapter(new ArrayAdapter<String>(RemoteAuthClientUI.this, android.R.layout.simple_list_item_1, mDevices));
						}

					})
					.create();
					mDialog.show();
					break;
				}
			}
		})
		.create();
		mDialog.show();
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, view, menuInfo);
		menu.add(0, REMOVE_ID, 0, R.string.mn_remove);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case REMOVE_ID:
			// remove device
			int id = (int) ((AdapterContextMenuInfo) item.getMenuInfo()).id;
			int p = 0;
			String[] devices = new String[mDevices.length - 1];
			for (int i = 0, l = mDevices.length; i < l; i++) {
				if (!mDevices[i].equals(mDevices[id])) {
					devices[p++] = mDevices[i];
				}
			}
			mDevices = devices;
			setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mDevices));
			return true;
		}
		return super.onContextItemSelected(item);
	}

	@Override
	public void onClick(View v) {
		if (v.equals(mBtn_add)) {
			// Get a set of currently paired devices
			BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
			Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
			// launch dialog to select device
			if (pairedDevices.size() > 0) {
				mPairedDevices = new String[pairedDevices.size()];
				int i = 0;
				for (BluetoothDevice device : pairedDevices) {
					mPairedDevices[i++] = device.getName() + " " + device.getAddress();
				}
				mDialog = new AlertDialog.Builder(this)
				.setItems(mPairedDevices, new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						addNewDevice(mPairedDevices[which]);
					}

				})
				.setPositiveButton(getString(R.string.btn_scan), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						mUnpairedDevices = new String[0];
						if (mServiceInterface != null) {
							try {
								mServiceInterface.requestDiscovery();
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
					}
				})
				.create();
				mDialog.show();
			} else {
				mUnpairedDevices = new String[0];
				if (mServiceInterface != null) {
					try {
						mServiceInterface.requestDiscovery();
						mProgressDialog = new ProgressDialog(this);
						mProgressDialog.setMessage("scanning for new devices");
						mProgressDialog.setCancelable(true);
						mProgressDialog.show();
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
			}
		}
	}

	private void pendingTagEvent() {
		boolean exists = false;
		for (String device : mDevices) {
			String name = parseDeviceString(device)[0];
			if (name.equals(mPendingTagEvent)) {
				exists = true;
				break;
			}
		}
		if (exists) {
			if (mServiceInterface != null) {
				try {
					String[] parsedDevice = parseDeviceString(mDevice);
					//					mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], "toggle", mChk_sec.isChecked(), parsedDevice[DEVICE_PASSPHRASE]);
					mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], "toggle", parsedDevice[DEVICE_PASSPHRASE]);
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			}
		}
		mPendingTagEvent = null;
	}

	protected static String[] parseDeviceString(String device) {
		String[] parsedDevice = new String[3];
		int nameEnd = device.indexOf(" ");
		if (nameEnd != -1) {
			parsedDevice[DEVICE_NAME] = device.substring(0, nameEnd);
			nameEnd++;
			String passphrase = device.substring(nameEnd);
			int passphraseEndTest = passphrase.indexOf(" ");
			int passphraseEnd = -1;
			while ((passphraseEndTest != -1) && (passphraseEnd < passphrase.length())) {
				passphraseEnd = passphraseEndTest;
				passphraseEndTest = passphrase.indexOf(" ", (passphraseEnd + 1));
			}
			if (passphraseEnd != -1) {
				// there's a passphrase
				parsedDevice[DEVICE_PASSPHRASE] = passphrase.substring(0, passphraseEnd);
			} else {
				parsedDevice[DEVICE_PASSPHRASE] = "";
			}
			if (device.length() > 17) {
				parsedDevice[DEVICE_ADDRESS] = device.substring(device.length() - 17);
			} else {
				parsedDevice[DEVICE_ADDRESS] = "";
			}
		} else {
			parsedDevice = new String[]{"", "", ""};
		}
		return parsedDevice;
	}

	protected static String buildDeviceString(String[] parsedDevice) {
		StringBuilder device = new StringBuilder();
		for (String item : parsedDevice) {
			if (device.length() > 0) {
				device.append(" ");
			}
			device.append(item);
		}
		return device.toString();
	}

	private void addNewDevice(String newDevice) {
		boolean exists = false;
		if (newDevice.length() > 0) {
			for (String device : mDevices) {
				if (newDevice.equals(device)) {
					exists = true;
				}
			}
		} else {
			// this will prevent empty devices
			exists = true;
		}
		if (!exists) {
			// new device
			String[] devices = new String[mDevices.length + 1];
			for (int i = 0, l = mDevices.length; i < l; i++) {
				devices[i] = mDevices[i];
			}
			devices[mDevices.length] = newDevice;
			mDevices = devices;
			setListAdapter(new ArrayAdapter<String>(RemoteAuthClientUI.this, android.R.layout.simple_list_item_1, mDevices));
			mDevice = newDevice;
		}
	}

	private void write(Tag tag) {
		// write the device and address
		String lang = "en";
		// don't write the passphrase!
		byte[] textBytes = parseDeviceString(mDevice)[0].getBytes();
		byte[] langBytes = null;
		int langLength = 0;
		try {
			langBytes = lang.getBytes("US-ASCII");
			langLength = langBytes.length;
		} catch (UnsupportedEncodingException e) {
			Log.e(TAG, e.toString());
		}
		int textLength = textBytes.length;
		byte[] payload = new byte[1 + langLength + textLength];

		// set status byte (see NDEF spec for actual bits)
		payload[0] = (byte) langLength;

		// copy langbytes and textbytes into payload
		if (langBytes != null) {
			System.arraycopy(langBytes, 0, payload, 1, langLength);
		}
		System.arraycopy(textBytes, 0, payload, 1 + langLength, textLength);
		NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, 
				NdefRecord.RTD_TEXT, 
				new byte[0], 
				payload);
		NdefMessage message = new NdefMessage(new NdefRecord[]{record, NdefRecord.createApplicationRecord("com.piusvelte.remoteauthclient")});

		// Get an instance of Ndef for the tag.
		Ndef ndef = Ndef.get(tag);
		if (ndef != null) {
			try {
				ndef.connect();
				if (ndef.isWritable()) {
					ndef.writeNdefMessage(message);
				}
				ndef.close();
				Log.d(TAG, "tag written");
				Toast.makeText(getApplicationContext(), "tag written", Toast.LENGTH_LONG);
			} catch (IOException e) {
				Log.d(TAG, e.toString());
			} catch (FormatException e) {
				Log.d(TAG, e.toString());
			}
		} else {
			Log.d(TAG, "no ndef, format");
			NdefFormatable format = NdefFormatable.get(tag);
			if (format != null) {
				try {
					format.connect();
					format.format(message);
					format.close();
					Log.d(TAG, "tag written");
					Toast.makeText(getApplicationContext(), "tag written", Toast.LENGTH_LONG);
				} catch (IOException e) {
					Log.d(TAG, e.toString());
				} catch (FormatException e) {
					Log.d(TAG, e.toString());
				}
			}
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder binder) {
		mServiceInterface = IRemoteAuthClientService.Stub.asInterface(binder);
		if (mUIInterface != null) {
			try {
				mServiceInterface.setCallback(mUIInterface);
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		}
		if (mPendingTagEvent != null) {
			pendingTagEvent();
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		mServiceInterface = null;
	}
}