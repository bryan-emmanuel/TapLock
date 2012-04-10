package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class RemoteAuthClientService extends Service {
	private static final String TAG = "RemoteAuthClientService";
	protected static final String ACTION_NFC_READ = "com.piusvelte.remoteauthclient.NFC_READ";
	protected static final String EXTRA_TAGGED_DEVICE = "com.piusvelte.remoteauthclient.TAGGED_DEVICE";
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device
    private int mState = STATE_NONE;
	private BluetoothAdapter mBtAdapter;
	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private byte[] mPendingMessage;
	private String mPendingAddress;
	private boolean mStartedBT = false;
	// Unique UUID for this application
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");//"963b082a-9f01-433d-8478-c26b16ea5da1");
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
			if (mUIInterface != null) {
				try {
					mUIInterface.setMessage("write: " + message + ", to: " + address);
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			}
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
					if (mUIInterface != null) {
						try {
							mUIInterface.setMessage("not connected, connecting...");
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					mPendingMessage = out;
					connectDevice(address);
				}
			} else {
				if (mUIInterface != null) {
					try {
						mUIInterface.setMessage("btadapter disabled, enabling...");
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
				mPendingMessage = out;
				mPendingAddress = address;
				mStartedBT = true;
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

		@Override
		public void stop() throws RemoteException {
			if (mStartedBT) {
				mBtAdapter.disable();
			}
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
					if (mUIInterface != null) {
						try {
							mUIInterface.setMessage("...btadapter enabled");
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					stopConnectionThreads();
					if (mAcceptThread != null) {
						mAcceptThread.cancel();
						mAcceptThread = null;
					}
					setState(STATE_LISTEN);
					mAcceptThread = new AcceptThread();
					mAcceptThread.start();
					if ((mPendingMessage != null) && (mPendingAddress != null)) {
						if (mUIInterface != null) {
							try {
								mUIInterface.setMessage("pending message and address, connecting...");
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
						connectDevice(mPendingAddress);
						mPendingAddress = null;
					}
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopConnectionThreads();
					if (mAcceptThread != null) {
						mAcceptThread.cancel();
						mAcceptThread = null;
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
		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		registerReceiver(mReceiver, filter);
		if (mBtAdapter.isEnabled()) {
			setState(STATE_LISTEN);
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
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
	
	private synchronized void setState(int state) {
		if (mUIInterface != null) {
			try {
				mUIInterface.setMessage("new state: " + state);
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		}
		mState = state;
	}

	private void stopConnectionThreads() {
		if (mUIInterface != null) {
			try {
				mUIInterface.setMessage("stopConnectionThreads");
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		}
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
		if ((mConnectedThread == null) || (!mConnectedThread.isConnected(address))) {
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

		if (mBtAdapter.isDiscovering()) {
			mBtAdapter.cancelDiscovery();
		}

		// Start the thread to connect with the given device
		setState(STATE_CONNECTING);
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
		
		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}

		// Start the thread to manage the connection and perform transmissions
		setState(STATE_CONNECTED);
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();
	}

	private class AcceptThread extends Thread {
		// The local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
				tmp = mBtAdapter.listenUsingRfcommWithServiceRecord("RemoteAuthClientService", sRemoteAuthServerUUID);
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mmServerSocket = tmp;
		}

		public void run() {
			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			while (mState != STATE_CONNECTED) {
				try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
					break;
				}

				// If a connection was accepted
				if (socket != null) {
					synchronized (RemoteAuthClientService.this) {
						switch (mState) {
						case STATE_LISTEN:
						case STATE_CONNECTING:
							connected(socket, socket.getRemoteDevice());
							break;
						case STATE_NONE:
						case STATE_CONNECTED:
							// Either not ready or already connected. Terminate new socket.
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG, e.toString());
							}
							break;
						}
					}
				}
			}
		}

		public void cancel() {
			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
		}
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
			// Always cancel discovery because it will slow down a connection
			mBtAdapter.cancelDiscovery();

			// Make a connection to the BluetoothSocket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				Log.d(TAG,"ConnectThread connecting...");
				mmSocket.connect();
				if (mUIInterface != null) {
					try {
						mUIInterface.setMessage("connected");
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
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
			if (mPendingMessage != null) {
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
					if (mUIInterface != null) {
						try {
							mUIInterface.setMessage("echoed: " + message);
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
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
			try {
				mmOutStream.write(buffer);
				if (mUIInterface != null) {
					try {
						mUIInterface.setMessage("message sent");
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
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
