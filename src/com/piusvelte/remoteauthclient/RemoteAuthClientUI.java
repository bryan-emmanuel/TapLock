package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.PendingIntent;
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
import android.widget.ListView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Toast;

public class RemoteAuthClientUI extends ListActivity implements OnClickListener, ServiceConnection {
	private static final String TAG = "RemoteAuthClientUI";
	private Button mBtn_add;
	private AlertDialog mDialog;
	private String[] mDevices;
	private String[] mPairedDevices;
	private String[] mUnpairedDevices;
	//	private BluetoothAdapter mBtAdapter;
	private static final int REMOVE_ID = Menu.FIRST;
	private String mDevice;

	// NFC
	private NfcAdapter mNfcAdapter = null;
	private boolean mInWriteMode = false;

	//	private boolean mDisableAfterWrite = false;

	private IRemoteAuthClientService mServiceInterface;
	private IRemoteAuthClientUI.Stub mUIInterface = new IRemoteAuthClientUI.Stub() {

		@Override
		public void setMessage(String message) throws RemoteException {
			Log.d(TAG, message);
			Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
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
			if (mUnpairedDevices.length > 0) {
				mDialog = new AlertDialog.Builder(RemoteAuthClientUI.this)
				.setItems(mUnpairedDevices, new DialogInterface.OnClickListener() {					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						addNewDevice(mPairedDevices[which]);
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
		mBtn_add = ((Button)findViewById(R.id.btn_add));
		mBtn_add.setOnClickListener(this);
		//NFC
		mNfcAdapter = NfcAdapter.getDefaultAdapter(getApplicationContext());
	}

	@Override 
	public void onNewIntent(Intent intent) { 
		setIntent(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
		bindService(new Intent(this, RemoteAuthClientService.class), this, BIND_AUTO_CREATE);
		SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
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
		// handle NFC intents
		Intent intent = getIntent();
		if (intent != null) {
			String action = intent.getAction();
			Log.d(TAG,"action: " + action);
			Bundle extras = intent.getExtras();
			if (extras != null) {
				Set<String> keys = extras.keySet();
				Iterator<String> iter = keys.iterator();
				while (iter.hasNext()) {
					Log.d(TAG,"key: " + iter.next());
				}
			}
			if (mInWriteMode && action.equals(NfcAdapter.ACTION_TAG_DISCOVERED)) {
				Log.d(TAG,"attempt to write tag");
				Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
				if ((tag != null) && (mDevice != null)) {
					write(tag);
					mNfcAdapter.disableForegroundDispatch(this);
					mInWriteMode=false;
				}
			} else if (intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) {
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
							String taggedDevice = new String(payload, languageCodeLength + 1, payload.length - languageCodeLength - 1, textEncoding);
							boolean exists = false;
							for (String device : mDevices) {
								if (device.equals(taggedDevice)) {
									exists = true;
									break;
								}
							}
							if (exists) {
								mDevice = taggedDevice;
								if (mServiceInterface != null) {
									try {
										mServiceInterface.write(mDevice.substring(mDevice.length() - 17), "toggle");
									} catch (RemoteException e) {
										Log.e(TAG, e.toString());
									}
								}
							}
						} catch (UnsupportedEncodingException e) {
							// should never happen unless we get a malformed tag.
							throw new IllegalArgumentException(e);
						}
					}
				}
			}
		}
		setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mDevices));
	}

	@Override
	protected void onPause() {
		super.onPause();
		mDevice = null;
		mInWriteMode = false;
		mNfcAdapter.disableForegroundDispatch(this);
		if ((mDialog != null) && mDialog.isShowing()) {
			mDialog.cancel();
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
		unbindService(this);
	}

	@Override
	protected void onListItemClick(ListView list, final View view, int position, final long id) {
		super.onListItemClick(list, view, position, id);
		mDialog = new AlertDialog.Builder(this)
		.setItems(R.array.states, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				String state = getResources().getStringArray(R.array.states)[which];
				mDevice = mDevices[(int) id];
				if (state.equals("tag")) {
					// write the device to a tag
					IntentFilter discovery = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
					IntentFilter[] tagFilters = new IntentFilter[] {discovery};
					Intent i = new Intent(RemoteAuthClientUI.this, getClass());
					PendingIntent pi = PendingIntent.getActivity(RemoteAuthClientUI.this, 0, i, 0);
					mInWriteMode = true;
					mNfcAdapter.enableForegroundDispatch(RemoteAuthClientUI.this, pi, tagFilters, null);
					mDialog = new AlertDialog.Builder(RemoteAuthClientUI.this)
					.setTitle("Touch tag")
					.setPositiveButton(android.R.string.ok, null)
					.create();
					mDialog.show();
				} else if (state.equals("remove")) {
					// remove device
					int p = 0;
					String[] devices = new String[mDevices.length - 1];
					for (int i = 0, l = mDevices.length; i < l; i++) {
						if (!mDevices[i].equals(mDevice)) {
							devices[p++] = mDevices[i];
						}
					}
					mDevices = devices;
					setListAdapter(new ArrayAdapter<String>(RemoteAuthClientUI.this, android.R.layout.simple_list_item_1, mDevices));
				} else {
					// attempt to connect to the device
					if (mServiceInterface != null) {
						try {
							mServiceInterface.write(mDevice.substring(mDevice.length() - 17), state);
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
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
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
			}
		}
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
		byte[] textBytes = mDevice.getBytes();
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
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		mServiceInterface = null;
	}
}