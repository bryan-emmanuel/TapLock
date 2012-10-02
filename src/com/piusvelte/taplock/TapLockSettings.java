/*
 * TapLock
 * Copyright (C) 2012 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */
package com.piusvelte.taplock;

import static com.piusvelte.taplock.TapLockService.ACTION_LOCK;
import static com.piusvelte.taplock.TapLockService.ACTION_PASSPHRASE;
import static com.piusvelte.taplock.TapLockService.ACTION_REMOVE;
import static com.piusvelte.taplock.TapLockService.ACTION_TAG;
import static com.piusvelte.taplock.TapLockService.ACTION_TOGGLE;
import static com.piusvelte.taplock.TapLockService.ACTION_UNLOCK;
import static com.piusvelte.taplock.TapLockService.DEVICE_ADDRESS;
import static com.piusvelte.taplock.TapLockService.DEVICE_NAME;
import static com.piusvelte.taplock.TapLockService.DEVICE_PASSPHRASE;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
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
import android.widget.EditText;
import android.widget.ListView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Toast;

public class TapLockSettings extends ListActivity implements OnClickListener, ServiceConnection {
	private static final String TAG = "TapLockSettings";
	private Button mBtn_add;
	private ProgressDialog mProgressDialog;
	private AlertDialog mDialog;
	private String[] mDevices;
	private String[] mPairedDevices = new String[0];
	private String[] mUnpairedDevices = new String[0];
	private static final int REMOVE_ID = Menu.FIRST;

	// NFC
	private NfcAdapter mNfcAdapter = null;
	private boolean mInWriteMode = false;

	private ITapLockService mServiceInterface;
	private ITapLockUI.Stub mUIInterface = new ITapLockUI.Stub() {

		@Override
		public void setMessage(String message) throws RemoteException {
			Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
		}

		@Override
		public void setUnpairedDevice(String device) throws RemoteException {
			if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
				String[] unpairedDevices = new String[mUnpairedDevices.length + 1];
				for (int i = 0, l = mUnpairedDevices.length; i < l; i++)
					unpairedDevices[i] = mUnpairedDevices[i];
				unpairedDevices[mUnpairedDevices.length] = device;
				mUnpairedDevices = unpairedDevices;
			}
		}

		@Override
		public void setDiscoveryFinished() throws RemoteException {
			if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
				mProgressDialog.cancel();
				if (mUnpairedDevices.length > 0) {
					mDialog = new AlertDialog.Builder(TapLockSettings.this)
					.setItems(mUnpairedDevices, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							if (mServiceInterface != null) {
								String[] parts = TapLockSettings.parseDeviceString(mUnpairedDevices[which]);
								try {
									mServiceInterface.pairDevice(parts[DEVICE_ADDRESS]);
								} catch (RemoteException e) {
									Log.e(TAG, e.toString());
									Toast.makeText(getApplicationContext(), "service unavailable", Toast.LENGTH_SHORT).show();
								}
							} else
								Toast.makeText(getApplicationContext(), "service unavailable", Toast.LENGTH_SHORT).show();
						}
					})
					.create();
					mDialog.show();
				} else
					Toast.makeText(getApplicationContext(), "no unpaired devices found", Toast.LENGTH_SHORT).show();
			}
		}

		@Override
		public void setStateFinished() throws RemoteException {
			Intent intent = getIntent();
			if (intent != null) {
				if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()) && intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES))
					TapLockSettings.this.finish();
			}
		}

		@Override
		public void setPairingResult(String device) throws RemoteException {
			addNewDevice(device);
		}

		@Override
		public void setPassphrase(String address, String passphrase) throws RemoteException {
			if (address != null) {
				for (int i = 0, l = mDevices.length; i < l; i++) {
					String[] deviceParts = parseDeviceString(mDevices[i]);
					if (deviceParts[DEVICE_ADDRESS].equals(address)) {
						String device = buildDeviceString(new String[]{deviceParts[DEVICE_NAME], passphrase, deviceParts[DEVICE_ADDRESS]});
						// save the device
						mDevices[i] = device;
						setListAdapter(new ArrayAdapter<String>(TapLockSettings.this, android.R.layout.simple_list_item_1, mDevices));
						break;
					}
				}
			} else
				Toast.makeText(getApplicationContext(), "failed to set passphrase", Toast.LENGTH_SHORT).show();
		}

	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		registerForContextMenu(getListView());
		mBtn_add = ((Button) findViewById(R.id.btn_add));
		mBtn_add.setOnClickListener(this);
		//NFC
		mNfcAdapter = NfcAdapter.getDefaultAdapter(getApplicationContext());

	}

	@Override 
	public void onNewIntent(Intent intent) {
		// handle NFC intents
		setIntent(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
		Intent intent = getIntent();
		if (mInWriteMode) {
			if (intent != null) {
				String action = intent.getAction();
				if (mInWriteMode && NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) && intent.hasExtra(TapLockService.EXTRA_DEVICE_NAME)) {
					Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
					String name = intent.getStringExtra(TapLockService.EXTRA_DEVICE_NAME);
					Log.d(TAG, "write tag: " + name);
					if ((tag != null) && (name != null)) {
						// write the device and address
						String lang = "en";
						// don't write the passphrase!
						byte[] textBytes = name.getBytes();
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
								Toast.makeText(this, "tag written", Toast.LENGTH_LONG).show();
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
						mNfcAdapter.disableForegroundDispatch(this);
					}
				}
			}
			mInWriteMode = false;
		} else {
			final SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
			Set<String> devices = sp.getStringSet(getString(R.string.key_devices), null);
			if (devices != null) {
				mDevices = new String[devices.size()];
				int d = 0;
				for (String device : devices)
					mDevices[d++] = device;
			} else
				mDevices = new String[0];
			setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mDevices));

			// check if configuring a widget
			if (intent != null) {
				Bundle extras = intent.getExtras();
				if (extras != null) {
					final int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
						mDialog = new AlertDialog.Builder(TapLockSettings.this)
						.setTitle("Select device for widget")
						.setItems(mDevices, new DialogInterface.OnClickListener() {

							@Override
							public void onClick(DialogInterface dialog, int which) {
								// set the successful widget result
								Intent resultValue = new Intent();
								resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
								setResult(RESULT_OK, resultValue);

								// broadcast the new widget to update
								String[] deviceParts = parseDeviceString(mDevices[which]);
								dialog.cancel();
								TapLockSettings.this.finish();
								sendBroadcast(new Intent(TapLockSettings.this, TapLockWidget.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId).putExtra(TapLockService.EXTRA_DEVICE_NAME, deviceParts[DEVICE_NAME]).putExtra(TapLockService.EXTRA_DEVICE_ADDRESS, deviceParts[DEVICE_ADDRESS]));
							}
						})
						.create();
						mDialog.show();
					}
				}
			}
		}
		// start the service before binding so that the service stays around for faster future connections
		startService(new Intent(this, TapLockService.class));
		bindService(new Intent(this, TapLockService.class), this, BIND_AUTO_CREATE);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (!mInWriteMode) {
			if (mServiceInterface != null) {
				try {
					mServiceInterface.stop();
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
				unbindService(this);
			}
			if ((mDialog != null) && mDialog.isShowing())
				mDialog.cancel();
			if ((mProgressDialog != null) && mProgressDialog.isShowing())
				mProgressDialog.cancel();
			// save devices
			Set<String> devices = new HashSet<String>();
			for (String device : mDevices)
				devices.add(device);
			SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
			SharedPreferences.Editor spe = sp.edit();
			spe.putStringSet(getString(R.string.key_devices), devices);
			spe.commit();
		}
	}

	@Override
	protected void onListItemClick(ListView list, final View view, int position, final long id) {
		super.onListItemClick(list, view, position, id);
		mDialog = new AlertDialog.Builder(TapLockSettings.this)
		.setItems(R.array.actions_entries, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				String action = getResources().getStringArray(R.array.actions_values)[which];
				int deviceIdx = (int) id;
				String device = mDevices[deviceIdx];
				String[] parsedDevice = parseDeviceString(device);
				dialog.cancel();
				if (ACTION_UNLOCK.equals(action) || ACTION_LOCK.equals(action) || ACTION_TOGGLE.equals(action)) {
					// attempt to connect to the device
					if (mServiceInterface != null) {
						try {
							mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], action, null);
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				} else if (ACTION_TAG.equals(action)) {
					// write the device to a tag
					mInWriteMode = true;
					mNfcAdapter.enableForegroundDispatch(TapLockSettings.this,
							PendingIntent.getActivity(TapLockSettings.this, 0, new Intent(TapLockSettings.this, TapLockSettings.this.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(TapLockService.EXTRA_DEVICE_NAME, parsedDevice[DEVICE_NAME]), 0),
							new IntentFilter[] {new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)},
							null);
					Toast.makeText(TapLockSettings.this, "Touch tag", Toast.LENGTH_LONG).show();
				} else if (ACTION_REMOVE.equals(action)) {
					// remove device
					int p = 0;
					String[] devices = new String[mDevices.length - 1];
					for (int i = 0, l = mDevices.length; i < l; i++) {
						if (i != deviceIdx) {
							devices[p++] = mDevices[i];
						}
					}
					mDevices = devices;
					setListAdapter(new ArrayAdapter<String>(TapLockSettings.this, android.R.layout.simple_list_item_1, mDevices));
				} else if (ACTION_PASSPHRASE.equals(action))
					setPassphrase(parsedDevice, deviceIdx);
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
				if (!mDevices[i].equals(mDevices[id]))
					devices[p++] = mDevices[i];
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
			final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
			if (btAdapter.isEnabled()) {
				Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
				// launch dialog to select device
				if (pairedDevices.size() > 0) {
					mPairedDevices = new String[pairedDevices.size()];
					int i = 0;
					for (BluetoothDevice device : pairedDevices)
						mPairedDevices[i++] = device.getName() + " " + device.getAddress();
					mDialog = new AlertDialog.Builder(TapLockSettings.this)
					.setItems(mPairedDevices, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							addNewDevice(mPairedDevices[which]);
						}

					})
					.setPositiveButton(getString(R.string.btn_bt_scan), new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							//						startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
							mUnpairedDevices = new String[0];
							if (mServiceInterface != null) {
								try {
									mServiceInterface.requestDiscovery();
									mProgressDialog = new ProgressDialog(TapLockSettings.this);
									mProgressDialog.setMessage(getString(R.string.msg_scanning));
									mProgressDialog.setCancelable(true);
									mProgressDialog.show();
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
							mProgressDialog.setMessage(getString(R.string.msg_scanning));
							mProgressDialog.setCancelable(true);
							mProgressDialog.show();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
			} else {
				mDialog = new AlertDialog.Builder(TapLockSettings.this)
				.setMessage(R.string.msg_enable_bt)
				.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						btAdapter.enable();
					}
				})
				.create();
				mDialog.show();
			}
		}
	}

	protected void setPassphrase(final String[] parsedDevice, final int deviceIdx) {
		final EditText fld_passphrase = new EditText(TapLockSettings.this);
		// parse the existing passphrase
		fld_passphrase.setText(parsedDevice[DEVICE_PASSPHRASE]);
		mDialog = new AlertDialog.Builder(TapLockSettings.this)
		.setTitle("set passphrase")
		.setView(fld_passphrase)
		.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.cancel();
				boolean storeImmediately = false;
				if (mServiceInterface != null) {
					try {
						mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], ACTION_PASSPHRASE, fld_passphrase.getText().toString());
						storeImmediately = true;
					} catch (RemoteException e) {
						e.printStackTrace();
					}
				}
				if (!storeImmediately) {
					String device = buildDeviceString(new String[]{parsedDevice[DEVICE_NAME], fld_passphrase.getText().toString(), parsedDevice[DEVICE_ADDRESS]});
					// save the device
					mDevices[deviceIdx] = device;
					setListAdapter(new ArrayAdapter<String>(TapLockSettings.this, android.R.layout.simple_list_item_1, mDevices));
				}
			}

		})
		.create();
		mDialog.show();
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
			if (passphraseEnd != -1)
				parsedDevice[DEVICE_PASSPHRASE] = passphrase.substring(0, passphraseEnd);
			else
				parsedDevice[DEVICE_PASSPHRASE] = "";
			if (device.length() > 17)
				parsedDevice[DEVICE_ADDRESS] = device.substring(device.length() - 17);
			else
				parsedDevice[DEVICE_ADDRESS] = "";
		} else {
			parsedDevice = new String[]{"", "", ""};
		}
		return parsedDevice;
	}

	protected static String buildDeviceString(String[] parsedDevice) {
		StringBuilder device = new StringBuilder();
		for (String item : parsedDevice) {
			if (device.length() > 0)
				device.append(" ");
			device.append(item);
		}
		return device.toString();
	}

	private void addNewDevice(String newDevice) {
		// new device
		String[] devices = new String[mDevices.length + 1];
		for (int i = 0, l = mDevices.length; i < l; i++)
			devices[i] = mDevices[i];
		String[] newDeviceParts = parseDeviceString(newDevice);
		// set the initial passphrase
		newDeviceParts[DEVICE_PASSPHRASE] = "TapLock";
		devices[mDevices.length] = buildDeviceString(newDeviceParts);
		mDevices = devices;
		setListAdapter(new ArrayAdapter<String>(TapLockSettings.this, android.R.layout.simple_list_item_1, mDevices));
		// set the passphrase
		setPassphrase(TapLockSettings.parseDeviceString(newDevice), mDevices.length - 1);
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder binder) {
		mServiceInterface = ITapLockService.Stub.asInterface(binder);
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