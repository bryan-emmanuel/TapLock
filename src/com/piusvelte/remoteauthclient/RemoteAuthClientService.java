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
	private BluetoothAdapter mBtAdapter;
	private AcceptThread mSecureAcceptThread;
	private AcceptThread mInsecureAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private byte[] mPendingMessage;
	private String mPendingAddress;
	private boolean mPendingSecure = false;
	private boolean mPendingDiscovery = false;
	private boolean mStartedBT = false;
	// Unique UUID for this application
	private static final String sSPD = "RemoteAuth";
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null) {
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
			}
		}

		@Override
		public void write(String address, String message, boolean secure) throws RemoteException {
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
					connectDevice(address, secure);
				}
			} else {
				mPendingMessage = out;
				mPendingAddress = address;
				mPendingSecure = secure;
				mStartedBT = true;
				mBtAdapter.enable();
			}
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			mPendingDiscovery = true;
			if (mBtAdapter.isEnabled()) {
				// If we're already discovering, stop it
				if (mBtAdapter.isDiscovering()) {
					mBtAdapter.cancelDiscovery();
				}
				// Request discover from BluetoothAdapter
				mBtAdapter.startDiscovery();
			} else {
				mBtAdapter.enable();
			}
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
							mUIInterface.setMessage("...btadapter enabled...");
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					stopConnectionThreads();
					if ((mPendingMessage != null) && (mPendingAddress != null)) {
						connectDevice(mPendingAddress, mPendingSecure);
					} else if (mPendingDiscovery) {
						mBtAdapter.startDiscovery();
					}
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopConnectionThreads();
					stopAcceptThreads();
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// If it's already paired, skip it, because it's been listed already
				if (mPendingDiscovery) {
					if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
						if (mUIInterface != null) {
							try {
								mUIInterface.setUnpairedDevice(device.getName() + " " + device.getAddress());
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
					}
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (mPendingDiscovery) {
					mPendingDiscovery = false;
					if (mUIInterface != null) {
						try {
							mUIInterface.setDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
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
		stopAcceptThreads();
	}

	private synchronized void setListen(boolean listen) {
		if (listen) {
			if (mSecureAcceptThread != null) {
				mSecureAcceptThread.cancel();
				mSecureAcceptThread = null;
			}
			mSecureAcceptThread = new AcceptThread(true);
			mSecureAcceptThread.start();
			mInsecureAcceptThread = new AcceptThread(false);
			mInsecureAcceptThread.start();
		} else {
			stopAcceptThreads();
		}
	}
	
	private void stopAcceptThreads() {
		if (mSecureAcceptThread != null) {
			mSecureAcceptThread.cancel();
			mSecureAcceptThread = null;
		}
		if (mInsecureAcceptThread != null) {
			mInsecureAcceptThread.cancel();
			mInsecureAcceptThread = null;
		}
	}

	private void stopConnectionThreads() {
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

	public synchronized void connectDevice(String address, boolean secure) {
		// don't reconnect if already connected
		if ((mConnectedThread == null) || (!mConnectedThread.isConnected(address))) {
			Log.d(TAG, "connectDevice " + address);
			BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
			mConnectThread = new ConnectThread(device, secure);
			if (mConnectThread.hasSocket()) {
				Log.d(TAG, "has socket");
				mConnectThread.start();
			} else {
				mConnectThread = null;
				setListen(true);
			}
		}
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * @param socket  The BluetoothSocket on which the connection was made
	 * @param device  The BluetoothDevice that has been connected
	 */
	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
		stopConnectionThreads();

		// Start the thread to manage the connection and perform transmissions
		setListen(false);
		mConnectedThread = new ConnectedThread(socket);
		if (mConnectedThread.hasStreams()) {
			Log.d(TAG, "has streams");
			mConnectedThread.start();
		} else {
			mConnectedThread = null;
			setListen(true);
		}
	}

	private class AcceptThread extends Thread {
		// The local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread(boolean secure) {
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
				if (secure) {
					tmp = mBtAdapter.listenUsingRfcommWithServiceRecord(sSPD, sRemoteAuthServerUUID);
				} else {
					tmp = mBtAdapter.listenUsingInsecureRfcommWithServiceRecord(sSPD, sRemoteAuthServerUUID);
				}
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mmServerSocket = tmp;
		}

		public void run() {
			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			while (mConnectedThread == null) {
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
						if (mConnectedThread != null) {
							// Either not ready or already connected. Terminate new socket.
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG, e.toString());
							}
							break;
						} else {
							connected(socket, socket.getRemoteDevice());
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
		private BluetoothSocket mmSocket;
		private BluetoothDevice mmDevice;

		public ConnectThread(BluetoothDevice device, boolean secure) {
			if (mBtAdapter.isDiscovering()) {
				mBtAdapter.cancelDiscovery();
			}
			
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				Log.d(TAG, "create socket");
				if (secure) {
					tmp = device.createRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
				} else {
					tmp = device.createInsecureRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
				}
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mmSocket = tmp;
		}

		public boolean hasSocket() {
			return (mmSocket != null);
		}

		public void run() {

			// Make a connection to the BluetoothSocket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				Log.d(TAG, "socket connect");
				mmSocket.connect();
			} catch (IOException e) {
				Log.e(TAG, e.toString());
				// Close the socket
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, e2.toString());
				} finally {
					mmSocket = null;
				}
				// fallback to listening
				setListen(true);
				return;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice);

			// Reset the ConnectThread because we're done
			synchronized (RemoteAuthClientService.this) {
				mConnectThread = null;
			}
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

		public boolean hasStreams() {
			return (mmInStream != null) && (mmOutStream != null);
		}

		public void run() {
			if (mPendingMessage != null) {
				write(mPendingMessage);
				mPendingMessage = null;
			}

			// Keep listening to the InputStream while connected
			while (true) {
				try {
					byte[] buffer = new byte[1024];
					int readBytes = mmInStream.read(buffer);
					// construct a string from the valid bytes in the buffer
					String message = new String(buffer, 0, readBytes);
					Log.d(TAG, "message: " + message);
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
