package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
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
import android.widget.TextView;
import android.widget.Toast;

public class RemoteAuthClientUI extends ListActivity implements OnClickListener {
	private static final String TAG = "RemoteAuthClientUI";
	private Button mBtn_lock;
	private Button mBtn_unlock;
	private Button mBtn_add;
	private Button mBtn_save;
	private Button mBtn_write;
	private AlertDialog mDialog;
	private TextView mFld_device;
	private TextView mFld_address;
	private String[] mDevices;
	private String[] mPairedDevices;
	private String[] mUnpairedDevices;
	private BluetoothAdapter mBtAdapter;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private static final int REMOVE_ID = Menu.FIRST;

	// Unique UUID for this application
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("963b082a-9f01-433d-8478-c26b16ea5da1");

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		registerForContextMenu(getListView());
		mFld_device = ((TextView)findViewById(R.id.fld_device));
		mFld_address = ((TextView)findViewById(R.id.fld_address));
		mBtn_lock = ((Button)findViewById(R.id.btn_lock));
		mBtn_unlock = ((Button)findViewById(R.id.btn_unlock));
		mBtn_add = ((Button)findViewById(R.id.btn_add));
		mBtn_save = ((Button)findViewById(R.id.btn_save));
		mBtn_write = ((Button)findViewById(R.id.btn_write));
		mBtn_lock.setOnClickListener(this);
		mBtn_unlock.setOnClickListener(this);
		mBtn_add.setOnClickListener(this);
		mBtn_save.setOnClickListener(this);
		mBtn_write.setOnClickListener(this);
		//BT
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	@Override 
	public void onNewIntent(Intent intent) { 
		setIntent(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
		IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		registerReceiver(mReceiver, filter);
		filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		registerReceiver(mReceiver, filter);
		filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		registerReceiver(mReceiver, filter);
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
		setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mDevices));
	}

	@Override
	protected void onPause() {
		super.onPause();
		if ((mDialog != null) && mDialog.isShowing()) {
			mDialog.cancel();
		}
		stopConnectionThreads();
		unregisterReceiver(mReceiver);
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
		int which = (int) id;
		// update device
		String device = mDevices[which];
		int i = device.length() - 17;
		String address = device.substring(i);
		mFld_device.setText(device.substring(0, i - 1));
		mFld_address.setText(address);
		// attempt to connect to the device
		if (mBtAdapter.isEnabled()) {
			connectDevice(address, false);
		} else {
			// ask to enable
			mDialog = new AlertDialog.Builder(this)
			.setTitle(R.string.ttl_enablebt)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					mBtAdapter.enable();
				}
			})
			.create();
			mDialog.show();
		}
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
		if (v.equals(mBtn_save)) {
			// disconnect
			stopConnectionThreads();
			// save the device
			String newDevice = mFld_device.getText().toString() + " " + mFld_address.getText().toString();
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
				setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mDevices));
			}
			mFld_device.setText("");
			mFld_address.setText("");
		} else if (v.equals(mBtn_write)) {
			// write the device to a tag
			//TODO
		} else {
			// these action require bluetooth
			if (!mBtAdapter.isEnabled()) {
				// ask to enable
				mDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.ttl_enablebt)
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						mBtAdapter.enable();
					}
				})
				.create();
				mDialog.show();
			} else {
				if (v.equals(mBtn_lock)) {
					// lock
					if (mConnectedThread != null) {
						Toast.makeText(getApplicationContext(), "write: lock", Toast.LENGTH_LONG).show();
						write("lock".getBytes());
					}
				} else if (v.equals(mBtn_unlock)) {
					// unlock
					if (mConnectedThread != null) {
						Toast.makeText(getApplicationContext(), "write: unlock", Toast.LENGTH_LONG).show();
						write("unlock".getBytes());
					}
				} else if (v.equals(mBtn_add)) {
					// Get a set of currently paired devices
					Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();
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
								// compare the paired devices to the saved devices
								String device = mPairedDevices[which];
								int i = device.length() - 17;
								mFld_device.setText(device.substring(0, i - 1));
								mFld_address.setText(device.substring(i));
							}

						})
						.setPositiveButton(getString(R.string.btn_scan), new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								doDiscovery();
							}
						})
						.create();
						mDialog.show();
					} else {
						doDiscovery();
					}
				}
			}
		}
	}

	// The BroadcastReceiver that listens for discovered devices and
	// changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_ON) {
					Toast.makeText(getApplicationContext(), "bluetooth enabled", Toast.LENGTH_LONG).show();
					String address = mFld_address.getText().toString();
					if (!address.equals("")) {
						// attempt to connect to the device
						connectDevice(address, false);
					}
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// If it's already paired, skip it, because it's been listed already
				if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
					// new unpaired device
					String[] newDevices = new String[mUnpairedDevices.length + 1];
					for (int i = 0, l = mUnpairedDevices.length; i < l; i++) {
						newDevices[i] = mUnpairedDevices[i];
					}
					newDevices[mUnpairedDevices.length] = device.getName() + " " + device.getAddress();
					mUnpairedDevices = newDevices;

				}
				// When discovery is finished, change the Activity title
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				// finished
				if (mUnpairedDevices.length > 0) {
					mDialog = new AlertDialog.Builder(RemoteAuthClientUI.this)
					.setItems(mUnpairedDevices, new DialogInterface.OnClickListener() {					
						@Override
						public void onClick(DialogInterface dialog, int which) {
							String device = mUnpairedDevices[which];
							int i = device.length() - 17;
							mFld_device.setText(device.substring(0, i - 1));
							mFld_address.setText(device.substring(i));
						}
					})
					.create();
					mDialog.show();
				} else {
					Toast.makeText(getApplicationContext(), "no devices discovered", Toast.LENGTH_LONG).show();
				}
			}
		}
	};

	private void stopConnectionThreads() {
		Log.d(TAG, "stopConnectionThreads");
		// Cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
	}

	/**
	 * Start device discover with the BluetoothAdapter
	 */
	private void doDiscovery() {
		mUnpairedDevices = new String[0];
		// If we're already discovering, stop it
		if (mBtAdapter.isDiscovering()) {
			mBtAdapter.cancelDiscovery();
		}

		Toast.makeText(getApplicationContext(), "start scan", Toast.LENGTH_LONG).show();
		// Request discover from BluetoothAdapter
		mBtAdapter.startDiscovery();
	}

	private void connectDevice(String address, boolean secure) {
		Toast.makeText(getApplicationContext(), "connect: " + address, Toast.LENGTH_LONG).show();
		// Get the BluetoothDevice object
		BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
		// Attempt to connect to the device
		connect(device, secure);
	}

	/**
	 * Start the ConnectThread to initiate a connection to a remote device.
	 * @param device  The BluetoothDevice to connect
	 * @param secure Socket Security type - Secure (true) , Insecure (false)
	 */
	public synchronized void connect(BluetoothDevice device, boolean secure) {
		// stop any existing connections
		stopConnectionThreads();

		// Start the thread to connect with the given device
		mConnectThread = new ConnectThread(device, secure);
		mConnectThread.start();
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * @param socket  The BluetoothSocket on which the connection was made
	 * @param device  The BluetoothDevice that has been connected
	 */
	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device, final String socketType) {
		stopConnectionThreads();

		// Start the thread to manage the connection and perform transmissions
		mConnectedThread = new ConnectedThread(socket, socketType);
		mConnectedThread.start();
	}

	/**
	 * Write to the ConnectedThread in an unsynchronized manner
	 * @param out The bytes to write
	 * @see ConnectedThread#write(byte[])
	 */
	public void write(byte[] out) {
		// Create temporary object
		ConnectedThread r;
		// Synchronize a copy of the ConnectedThread
		synchronized (this) {
			if (mConnectedThread == null) {
				return;
			}
			r = mConnectedThread;
		}
		// Perform the write unsynchronized
		r.write(out);
	}

	/**
	 * This thread runs while attempting to make an outgoing connection
	 * with a device. It runs straight through; the connection either
	 * succeeds or fails.
	 */
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;
		private String mSocketType;

		public ConnectThread(BluetoothDevice device, boolean secure) {
			Log.d(TAG,"create ConnectThread");
			mmDevice = device;
			BluetoothSocket tmp = null;
			mSocketType = secure ? "Secure" : "Insecure";

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				if (secure) {
					tmp = device.createRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
				} else {
					tmp = device.createInsecureRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
				}
			} catch (IOException e) {
				Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
			}
			mmSocket = tmp;
		}

		public void run() {
			Log.d(TAG,"start ConnectThread, SocketType: " + mSocketType);
			setName("ConnectThread" + mSocketType);

			// Always cancel discovery because it will slow down a connection
			mBtAdapter.cancelDiscovery();

			// Make a connection to the BluetoothSocket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				Log.d(TAG,"ConnectThread ***connect***");
				mmSocket.connect();
			} catch (IOException e) {
				// Close the socket
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, "unable to close() " + mSocketType + " socket during connection failure", e2);
				}
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (RemoteAuthClientUI.this) {
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice, mSocketType);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect " + mSocketType + " socket failed", e);
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device.
	 * It handles all incoming and outgoing transmissions.
	 */
	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;

		public ConnectedThread(BluetoothSocket socket, String socketType) {
			Log.d(TAG, "create ConnectedThread: " + socketType);
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the BluetoothSocket input and output streams
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			Log.d(TAG, "start ConnectedThread");

			// Keep listening to the InputStream while connected
			while (true) {
				try {
					byte[] buffer = new byte[1024];
					int readBytes = mmInStream.read(buffer);
	                // construct a string from the valid bytes in the buffer
	                String message = new String(buffer, 0, readBytes);
					Log.d(TAG,"message: "+message);
				} catch (IOException e) {
					Log.e(TAG, "disconnected", e);
					break;
				}
			}
		}

		/**
		 * Write to the connected OutStream.
		 * @param buffer  The bytes to write
		 */
		public void write(byte[] buffer) {
			Log.d(TAG,"writing to outstream");
			try {
				mmOutStream.write(buffer);
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
}