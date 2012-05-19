package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Set;
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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class RemoteAuthClientService extends Service {
	private static final String TAG = "RemoteAuthClientService";
	private static final boolean sDebug = false;
	public static final String ACTION_TOGGLE = "com.piusvelte.remoteAuthClient.ACTION_TOGGLE";
	public static final String EXTRA_DEVICE_ADDRESS = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_ADDRESS";
	public static final String EXTRA_DEVICE_NAME = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_NAME";
	public static final String EXTRA_DEVICE_STATE = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_STATE";
	private BluetoothAdapter mBtAdapter;
	//	private AcceptThread mSecureAcceptThread;
	private AcceptThread mInsecureAcceptThread;
	private ConnectThread mConnectThread;
	//	private ConnectedThread mConnectedThread;
	private String mPendingState;
	private String mPendingAddress;
	private String mPendingUuid;
//	private boolean mPendingSecure = false;
	private String mPendingPassphrase;
	private static final int REQUEST_NONE = 0;
	private static final int REQUEST_WRITE = 1;
	private static final int REQUEST_DISCOVERY = 2;
	private int REQUEST = REQUEST_NONE;
	private boolean mStartedBT = false;
	// Unique UUID for this application
	private static final String sSPD = "RemoteAuth";
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	protected String mMessage = "";
	protected final Handler mHandler = new Handler();
	protected final Runnable mRunnable = new Runnable() {
		@SuppressWarnings("unused")
		public void run() {
			if (sDebug && (mUIInterface != null)) {
				try {
					mUIInterface.setMessage(mMessage);
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			}
			Log.d(TAG, mMessage);
		}
	};

	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null) {
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
			}
		}

		@Override
		public void write(String address, String state, String passphrase) throws RemoteException {
			requestWrite(address, state, passphrase);
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			setRequest(REQUEST_DISCOVERY);
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
					stopConnectionThreads();
					int request = getRequest();
					switch (request) {
					case REQUEST_WRITE:
						if (hasPendingRequest()) {
							connectDevice(null);
						}
						break;
					case REQUEST_DISCOVERY:
						mBtAdapter.startDiscovery();
						break;
					default:
						break;
					}
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopConnectionThreads();
					setListen(false, null);
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				int request = getRequest();
				switch (request) {
				case REQUEST_WRITE:
					if (hasPendingRequest() && (device.getAddress().equals(mPendingAddress))) {
						connectDevice(device);
					}
					break;
				case REQUEST_DISCOVERY:
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
					break;
				default:
					break;
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				int request = getRequest();
				switch (request) {
				case REQUEST_WRITE:
					// even if a pending request exists, it should have been triggered in the discovery phase
					setRequest(REQUEST_NONE);
					break;
				case REQUEST_DISCOVERY:
					if (mUIInterface != null) {
						try {
							mUIInterface.setDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					setRequest(REQUEST_NONE);
					break;
				default:
					break;
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
		if (intent != null) {
			String action = intent.getAction();
			if ((action != null) && action.equals(ACTION_TOGGLE) && intent.hasExtra(EXTRA_DEVICE_ADDRESS)) {
				requestWrite(intent.getStringExtra(EXTRA_DEVICE_ADDRESS));
			}
		}
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
		setListen(false, null);
	}
	
	private void requestWrite(String address) {
		// need to get the passphrase
		SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
		Set<String> devices = sp.getStringSet(getString(R.string.key_devices), null);
		if (devices != null) {
			Iterator<String> iter = devices.iterator();
			while (iter.hasNext()) {
				String device = iter.next();
				String[] deviceParts = RemoteAuthClientUI.parseDeviceString(device);
				if (deviceParts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
					requestWrite(address, Integer.toString(RemoteAuthClientUI.STATE_TOGGLE), deviceParts[RemoteAuthClientUI.DEVICE_PASSPHRASE]);
					break;
				}
			}
		}
	}

	private void requestWrite(String address, String state, String passphrase) {
		String uuid = sRemoteAuthServerUUID.toString();
		setRequest(REQUEST_WRITE);
		if (mBtAdapter.isEnabled()) {
			//				if ((mConnectedThread != null) && mConnectedThread.isConnected(address)) {
			if ((mConnectThread != null) && mConnectThread.isConnected(address)) {
				// Create temporary object
				ConnectThread r;
				// Synchronize a copy of the ConnectedThread
				synchronized (this) {
					r = mConnectThread;
				}
				// Perform the write unsynchronized
				r.write(state);
			} else {
				setPendingRequest(state, address, uuid, passphrase);
				connectDevice(address, uuid, passphrase, null);
			}
		} else {
			setPendingRequest(state, address, uuid, passphrase);
			mStartedBT = true;
			mBtAdapter.enable();
		}
	}
	
	private synchronized void setListen(boolean listen, String passphrase) {
		if (listen) {
			mMessage = "start listen";
			mHandler.post(mRunnable);
			//			if (mSecureAcceptThread != null) {
			//				mSecureAcceptThread.cancel();
			//				mSecureAcceptThread = null;
			//			}
			//			mSecureAcceptThread = new AcceptThread(true, passphrase);
			//			mSecureAcceptThread.start();
			if (mInsecureAcceptThread != null) {
				mInsecureAcceptThread.cancel();
				mInsecureAcceptThread = null;
			}
			mInsecureAcceptThread = new AcceptThread(passphrase);
			mInsecureAcceptThread.start();
			mBtAdapter.startDiscovery();
		} else {
			mMessage = "stop listen";
			mHandler.post(mRunnable);
			//			if (mSecureAcceptThread != null) {
			//				mSecureAcceptThread.cancel();
			//				mSecureAcceptThread = null;
			//			}
			if (mInsecureAcceptThread != null) {
				mInsecureAcceptThread.cancel();
				mInsecureAcceptThread = null;
			}
		}
	}

	private void stopConnectionThreads() {
		// Cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		//		if (mConnectedThread != null) {
		//			mConnectedThread.cancel();
		//			mConnectedThread = null;
		//		}
	}

	public BluetoothDevice getDevice(String address) {
		BluetoothDevice device = null;
		Set<BluetoothDevice> devices = mBtAdapter.getBondedDevices();
		Iterator<BluetoothDevice> iter = devices.iterator();
		while (iter.hasNext() && (device == null)) {
			BluetoothDevice d = iter.next();
			if (d.getAddress().equals(address)) {
				device = d;
			}
		}
		if (device == null) {
			device = mBtAdapter.getRemoteDevice(address);
		}
		return device;
	}

	public void setPendingRequest(String state, String address, String uuid, String passphrase) {
		mPendingState = state;
		mPendingAddress = address;
		mPendingUuid = uuid;
//		mPendingSecure = secure;
		mPendingPassphrase = passphrase;
	}

	public boolean hasPendingRequest() {
		return (mPendingState != null) && (mPendingAddress != null) && (mPendingUuid != null) && (mPendingPassphrase != null);
	}

	public synchronized int getRequest() {
		return REQUEST;
	}

	public synchronized void setRequest(int request) {
		REQUEST = request;
	}

	public synchronized void clearPendingRequest() {
		mPendingState = null;
		mPendingAddress = null;
		mPendingUuid = null;
		mPendingPassphrase = null;
	}

	public void connectDevice(BluetoothDevice device) {
		connectDevice(mPendingAddress, mPendingUuid, mPendingPassphrase, device);
	}

	public synchronized void connectDevice(String address, String uuid, String passphrase, BluetoothDevice device) {
		// don't reconnect if already connected
		//		if ((mConnectedThread == null) || (!mConnectedThread.isConnected(address))) {
		if ((mConnectThread == null) || (!mConnectThread.isConnected(address))) {
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
			mMessage = "connectDevice: " + address;
			mHandler.post(mRunnable);
			if (device == null) {
				device = getDevice(address);
			}
			mConnectThread = new ConnectThread(device, uuid, passphrase);
			if (mConnectThread.hasSocket()) {
				mConnectThread.start();
			} else {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		} else {
			mMessage = "already connected: " + address;
			mHandler.post(mRunnable);
		}
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 * @param socket  The BluetoothSocket on which the connection was made
	 * @param device  The BluetoothDevice that has been connected
	 */
	//	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device, String passphrase) {
	//		stopConnectionThreads();
	//		setListen(false, null);
	//
	//		mConnectedThread = new ConnectedThread(socket, passphrase);
	//		if (mConnectedThread.hasStreams()) {
	//			Log.d(TAG, "has streams");
	//			mConnectedThread.start();
	//		} else {
	//			mConnectedThread = null;
	//			setListen(true, passphrase);
	//		}
	//	}

	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device, String passphrase) {
		stopConnectionThreads();
		setListen(false, null);

		mConnectThread = new ConnectThread(socket, passphrase);
		if (mConnectThread.hasStreams()) {
			Log.d(TAG, "has streams");
			mConnectThread.start();
		} else {
			mConnectThread.cancel();
			mConnectThread = null;
		}
	}

	private class AcceptThread extends Thread {
		// The local server socket
		private BluetoothServerSocket mmServerSocket;

		private String mPassphrase;

		public AcceptThread(String passphrase) {
			mPassphrase = passphrase;
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
//				if (secure) {
//					tmp = mBtAdapter.listenUsingRfcommWithServiceRecord(sSPD, sRemoteAuthServerUUID);
//				} else {
				tmp = mBtAdapter.listenUsingInsecureRfcommWithServiceRecord(sSPD, sRemoteAuthServerUUID);
//				}
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mmServerSocket = tmp;
		}

		public void run() {
			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			//			while (mConnectedThread == null) {
			while (mConnectThread == null) {
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
					mMessage = "connected from peer";
					mHandler.post(mRunnable);
					synchronized (RemoteAuthClientService.this) {
						//						if (mConnectedThread != null) {
						if (mConnectThread != null) {
							// Either not ready or already connected. Terminate new socket.
							try {
								socket.close();
							} catch (IOException e) {
								Log.e(TAG, e.toString());
							}
							break;
						} else {
							connected(socket, socket.getRemoteDevice(), mPassphrase);
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
		private BluetoothSocket mSocket;
		private InputStream mInStream;
		private OutputStream mOutStream;
		//		private BluetoothDevice mDevice;

		private String mPassphrase;
		private String mChallenge;
		private MessageDigest mDigest;

		private boolean mConnected = true;

		public ConnectThread(BluetoothSocket socket, String passphrase) {
			mSocket = socket;
			mPassphrase = passphrase;
		}

		public ConnectThread(BluetoothDevice device, String uuid, String passphrase) {
			//			mDevice = device;
			mPassphrase = passphrase;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				Log.d(TAG, "create socket");
//				if (secure) {
//					tmp = device.createRfcommSocketToServiceRecord(UUID.fromString(uuid));
//				} else {
				tmp = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid));
//				}
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mSocket = tmp;
		}

		public boolean hasSocket() {
			return (mSocket != null);
		}

		public void run() {
			Log.d(TAG, "socket connect");
			mBtAdapter.cancelDiscovery();

			try {
				mSocket.connect();
				mMessage = "connected";
				mHandler.post(mRunnable);
			} catch (IOException e) {
				mMessage = "connection attempt failed: " + e.toString();
				mHandler.post(mRunnable);
				try {
					mSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, e2.toString());
				}
				mSocket = null;
			}

			//			// Reset the ConnectThread because we're done
			//			synchronized (RemoteAuthClientService.this) {
			//				mConnectThread = null;
			//			}

			if (hasSocket() && mSocket.isConnected()) {
				// Start the connected thread
				//				connected(mSocket, mDevice, mPassphrase);
				InputStream tmpIn = null;
				OutputStream tmpOut = null;

				// Get the BluetoothSocket input and output streams
				try {
					tmpIn = mSocket.getInputStream();
					tmpOut = mSocket.getOutputStream();
				} catch (IOException e) {
					Log.e(TAG, "temp sockets not created", e);
				}

				mInStream = tmpIn;
				mOutStream = tmpOut;

				if (hasStreams()) {
					while (mConnected) {
						byte[] buffer = new byte[1024];
						int readBytes = -1;
						try {
							readBytes = mInStream.read(buffer);
						} catch (IOException e) {
							mConnected = false;
							Log.e(TAG, e.toString());
						}
						if (readBytes != -1) {
							// construct a string from the valid bytes in the buffer
							String message = new String(buffer, 0, readBytes);
							mMessage = "message: " + message;
							mHandler.post(mRunnable);
							// listen for challenge, then process a response
							if ((message.length() > 10) && (message.substring(0, 9).equals("challenge"))) {
								mChallenge = message.substring(10);
								mMessage = "set challenge: " + mChallenge;
								mHandler.post(mRunnable);
								if (hasPendingRequest()) {
									mMessage = "pending request, state: " + mPendingState;
									mHandler.post(mRunnable);
									write(mPendingState);
								}
							} else if ((message.length() > 6) && (message.substring(0, 5).equals("state"))) {
								// update widgets
								//TODO: need to identify the widgets for this device
//								int state = Integer.parseInt(message.substring(6));
//								String response = "toggle";
//								if (state== RemoteAuthClientUI.STATE_UNLOCK) {
//									response = "lock";
//								} else if (state == RemoteAuthClientUI.STATE_LOCK) {
//									response = "unlock";
//								}
//								SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
//								Set<String> devices = sp.getStringSet(getString(R.string.key_devices), null);
//								if (devices != null) {
//									Iterator<String> iter = devices.iterator();
//									while (iter.hasNext()) {
//										String device = iter.next();
//										String[] deviceParts = RemoteAuthClientUI.parseDeviceString(device);
//										if (deviceParts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
//											requestWrite(address, Integer.toString(RemoteAuthClientUI.STATE_TOGGLE), false, deviceParts[RemoteAuthClientUI.DEVICE_PASSPHRASE]);
//											break;
//										}
//									}
//								}
							}
						} else {
							mConnected = false;
						}
					}
				} else {
					cancel();
				}
			} else {
				cancel();
			}
		}

		public void write(String state) {
			String challenge = getChallenge();
			if (challenge != null) {
				if (mDigest == null) {
					try {
						mDigest = MessageDigest.getInstance("SHA-256");
					} catch (NoSuchAlgorithmException e) {
						Log.e(TAG, e.toString());
					}
				}
				if ((challenge != null) && (mDigest != null)) {
					mDigest.reset();
					try {
						mDigest.update((challenge + mPassphrase + state).getBytes("UTF-8"));
						String request = new BigInteger(1, mDigest.digest()).toString(16);
						mMessage = "write state: " + state + ", challenge: " + challenge + ", request: " + request;
						mHandler.post(mRunnable);
						mOutStream.write(request.getBytes());
						clearPendingRequest();
					} catch (IOException e) {
						Log.e(TAG, "Exception during write", e);
						// need to get a new challenge
						try {
							mOutStream.write(("challenge").getBytes());
						} catch (IOException e1) {
							Log.e(TAG, "Exception during write", e1);
						}
					}
				}
			} else {
				// need to get a new challenge
				try {
					mOutStream.write(("challenge").getBytes());
				} catch (IOException e) {
					Log.e(TAG, "Exception during write", e);
				}
			}
			if (getRequest() == REQUEST_WRITE) {
				setRequest(REQUEST_NONE);
			}
		}

		public boolean hasStreams() {
			return (mInStream != null) && (mOutStream != null);
		}

		public boolean isConnected(String address) {
			if (mSocket != null) {
				BluetoothDevice device = mSocket.getRemoteDevice();
				if (device == null) {
					return false;
				} else {
					return device.getAddress().equals(address);
				}
			} else {
				return false;
			}
		}

		private synchronized String getChallenge() {
			// the challenge is stale after it's used
			String challenge = mChallenge;
			mChallenge = null;
			return challenge;
		}

		public void cancel() {
			mConnected = false;
			if (mSocket != null) {
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
			}
			// fallback to listening
			setListen(true, mPassphrase);
		}
	}

	/**
	 * This thread runs during a connection with a remote device.
	 * It handles all incoming and outgoing transmissions.
	 */
	//	private class ConnectedThread extends Thread {
	//		private BluetoothSocket mSocket;
	//		private InputStream mInStream;
	//		private OutputStream mOutStream;
	//
	//		private String mPassphrase;
	//		private String mChallenge;
	//		private MessageDigest mDigest;
	//
	//		private boolean mConnected = true;
	//
	//		public ConnectedThread(BluetoothSocket socket, String passphrase) {
	//			if ((socket == null) || (!socket.isConnected())) {
	//				Log.d(TAG, "connected failure, bad socket");
	//				return;
	//			}
	//			mSocket = socket;
	//			mPassphrase = passphrase;
	//			InputStream tmpIn = null;
	//			OutputStream tmpOut = null;
	//
	//			// Get the BluetoothSocket input and output streams
	//			try {
	//				tmpIn = socket.getInputStream();
	//				tmpOut = socket.getOutputStream();
	//			} catch (IOException e) {
	//				Log.e(TAG, "temp sockets not created", e);
	//			}
	//
	//			mInStream = tmpIn;
	//			mOutStream = tmpOut;
	//		}
	//
	//		public boolean hasStreams() {
	//			return (mInStream != null) && (mOutStream != null);
	//		}
	//
	//		public void run() {
	//
	//			// Keep listening to the InputStream while connected
	//			while (mConnected) {
	//				byte[] buffer = new byte[1024];
	//				int readBytes = -1;
	//				try {
	//					readBytes = mInStream.read(buffer);
	//				} catch (IOException e) {
	//					mConnected = false;
	//					Log.e(TAG, e.toString());
	//				}
	//				if (readBytes != -1) {
	//					// construct a string from the valid bytes in the buffer
	//					String message = new String(buffer, 0, readBytes);
	//					mMessage = "message: " + message;
	//					mHandler.post(mRunnable);
	//					// listen for challenge, then process a response
	//					if ((message.length() > 10) && (message.substring(0, 9).equals("challenge"))) {
	//						setChallenge(message.substring(10));
	//					}
	//				} else {
	//					mConnected = false;
	//				}
	//			}
	//		}
	//
	//		private synchronized void setChallenge(String challenge) {
	//			mMessage = "set challenge: " + challenge;
	//			mHandler.post(mRunnable);
	//			mChallenge = challenge;
	//			if (hasPendingRequest()) {
	//				mMessage = "pending request, state: " + mPendingState;
	//				mHandler.post(mRunnable);
	//				write(mPendingState);
	//			}
	//		}
	//
	//		private synchronized String getChallenge() {
	//			// the challenge is stale after it's used
	//			String challenge = mChallenge;
	//			mChallenge = null;
	//			return challenge;
	//		}
	//
	//		public boolean isConnected(String address) {
	//			BluetoothDevice device = mSocket.getRemoteDevice();
	//			if (device == null) {
	//				return false;
	//			} else {
	//				return device.getAddress().equals(address);
	//			}
	//		}
	//
	//		/**
	//		 * Write to the connected OutStream.
	//		 * @param buffer  The bytes to write
	//		 */
	//		public void write(String state) {
	//			String challenge = getChallenge();
	//			if (challenge != null) {
	//				if (mDigest == null) {
	//					try {
	//						mDigest = MessageDigest.getInstance("SHA-256");
	//					} catch (NoSuchAlgorithmException e) {
	//						Log.e(TAG, e.toString());
	//					}
	//				}
	//				if ((challenge != null) && (mDigest != null)) {
	//					mDigest.reset();
	//					try {
	//						mDigest.update((challenge + mPassphrase + state).getBytes("UTF-8"));
	//						String request = new BigInteger(1, mDigest.digest()).toString(16);
	//						mMessage = "write state: " + state + ", challenge: " + challenge + ", request: " + request;
	//						mHandler.post(mRunnable);
	//						mOutStream.write(request.getBytes());
	//						clearPendingRequest();
	//					} catch (IOException e) {
	//						Log.e(TAG, "Exception during write", e);
	//						// need to get a new challenge
	//						try {
	//							mOutStream.write(("challenge").getBytes());
	//						} catch (IOException e1) {
	//							Log.e(TAG, "Exception during write", e1);
	//						}
	//					}
	//				}
	//			} else {
	//				// need to get a new challenge
	//				try {
	//					mOutStream.write(("challenge").getBytes());
	//				} catch (IOException e) {
	//					Log.e(TAG, "Exception during write", e);
	//				}
	//			}
	//			if (getRequest() == REQUEST_WRITE) {
	//				setRequest(REQUEST_NONE);
	//			}
	//		}
	//
	//		public void cancel() {
	//			mConnected = false;
	//			if (mSocket != null) {
	//				try {
	//					mSocket.close();
	//				} catch (IOException e) {
	//					Log.e(TAG, "close() of connect socket failed", e);
	//				}
	//			}
	//		}
	//	}

}
