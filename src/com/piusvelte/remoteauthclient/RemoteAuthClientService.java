package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

public class RemoteAuthClientService extends Service {
	private static final String TAG = "RemoteAuthClientService";
	private BluetoothAdapter mBtAdapter;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private byte[] mPendingMessage;
	private String mPendingAddress;
	// Unique UUID for this application
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("963b082a-9f01-433d-8478-c26b16ea5da1");
	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {
		
		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null) {
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
			}
		}

		@Override
		public void write(String address, String message) throws RemoteException {
			byte[] out = message.getBytes();
			if (mBtAdapter.isEnabled()) {
				if ((mConnectedThread != null) && mConnectedThread.isConnected(address)) {
					// Create temporary object
					ConnectedThread r;
					// Synchronize a copy of the ConnectedThread
					synchronized (this) {
						r = mConnectedThread;
					}
					// Perform the write unsynchronized
					r.write(out);
				} else {
					mPendingMessage = out;
					connectDevice(address);
				}
			} else {
				mPendingMessage = out;
				mPendingAddress = address;
				mBtAdapter.enable();
			}
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			// If we're already discovering, stop it
			if (mBtAdapter.isDiscovering()) {
				mBtAdapter.cancelDiscovery();
			}
			// Request discover from BluetoothAdapter
			mBtAdapter.startDiscovery();
		}
	};
	
	// The BroadcastReceiver that listens for discovered devices and
	// changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_ON) {
					if ((mPendingMessage != null) && (mPendingAddress != null)) {
						connectDevice(mPendingAddress);
						mPendingAddress = null;
					}
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// If it's already paired, skip it, because it's been listed already
				if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
					if (mUIInterface != null) {
						try {
							mUIInterface.setUnpairedDevice(device.getName() + " " + device.getAddress());
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
				// When discovery is finished, change the Activity title
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (mUIInterface != null) {
					try {
						mUIInterface.setDiscoveryFinished();
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
			}
		}
	};
	
	@Override
	public void onCreate() {
		super.onCreate();
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		registerReceiver(mReceiver, filter);
		filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		registerReceiver(mReceiver, filter);
		filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		registerReceiver(mReceiver, filter);
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		onStart(intent, startId);
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return mServiceInterface;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		unregisterReceiver(mReceiver);
		stopConnectionThreads();
	}

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

	private void connectDevice(String address) {
		// don't reconnect if already connected
		if ((mConnectedThread != null) && (!mConnectedThread.isConnected(address))) {
			Toast.makeText(getApplicationContext(), "connect: " + address, Toast.LENGTH_LONG).show();
			// Get the BluetoothDevice object
			BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
			// Attempt to connect to the device
			connect(device);
		}
	}

	/**
	 * Start the ConnectThread to initiate a connection to a remote device.
	 * @param device  The BluetoothDevice to connect
	 * @param secure Socket Security type - Secure (true) , Insecure (false)
	 */
	public synchronized void connect(BluetoothDevice device) {
		// stop any existing connections
		stopConnectionThreads();

		// Start the thread to connect with the given device
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * @param socket  The BluetoothSocket on which the connection was made
	 * @param device  The BluetoothDevice that has been connected
	 */
	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
		stopConnectionThreads();

		// Start the thread to manage the connection and perform transmissions
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();
	}

	/**
	 * This thread runs while attempting to make an outgoing connection
	 * with a device. It runs straight through; the connection either
	 * succeeds or fails.
	 */
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		public ConnectThread(BluetoothDevice device) {
			Log.d(TAG,"create ConnectThread");
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
//				Method m = mBtAdapter.getClass().getMethod("createRfcommSocket", new Class[] { int.class });
//				tmp = (BluetoothSocket) m.invoke(device, Integer.valueOf(1));
				tmp = device.createRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
			} catch (IOException e) {
				Log.e(TAG, e.toString());
//			} catch (NoSuchMethodException e) {
//				Log.e(TAG, e.toString());
//			} catch (IllegalArgumentException e) {
//				Log.e(TAG, e.toString());
//			} catch (IllegalAccessException e) {
//				Log.e(TAG, e.toString());
//			} catch (InvocationTargetException e) {
//				Log.e(TAG, e.toString());
			}
			mmSocket = tmp;
		}

		public void run() {
			Log.d(TAG,"start ConnectThread");

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
					Log.e(TAG, e2.toString());
				}
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (RemoteAuthClientService.this) {
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, e.toString());
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

		public ConnectedThread(BluetoothSocket socket) {
			Log.d(TAG, "create ConnectedThread");
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
			if (mPendingMessage != null) {
				Log.d(TAG,"pending request: " + mPendingMessage);;
				write(mPendingMessage);
				mPendingMessage = null;
//				if (mDisableAfterWrite) {
//					mBtAdapter.disable();
//					mDisableAfterWrite = false;
//				}
			}

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
		
		public boolean isConnected(String address) {
			BluetoothDevice device = mmSocket.getRemoteDevice();
			if (device == null) {
				return false;
			} else {
				return device.getAddress().equals(address);
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
