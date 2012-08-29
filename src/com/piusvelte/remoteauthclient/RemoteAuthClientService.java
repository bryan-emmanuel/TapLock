package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.RemoteViews;

public class RemoteAuthClientService extends Service implements OnSharedPreferenceChangeListener {
	private static final String TAG = "RemoteAuthClientService";
	public static final String ACTION_TOGGLE = "com.piusvelte.remoteAuthClient.ACTION_TOGGLE";
	public static final String EXTRA_DEVICE_ADDRESS = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_ADDRESS";
	public static final String EXTRA_DEVICE_NAME = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_NAME";
	public static final String EXTRA_DEVICE_STATE = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_STATE";
	private BluetoothAdapter mBtAdapter;
	//	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private String mQueueAddress;
	private String mQueueState;
	private boolean mStartedBT = false;
	private String mDeviceFound = null;
	private String[] mDevices = new String[0];
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	protected String mMessage = "";
	protected final Handler mHandler = new Handler();
	protected final Runnable mRunnable = new Runnable() {
		public void run() {
			if (mMessage != null)
				Log.d(TAG, mMessage);
			if (mUIInterface != null) {
				try {
					if (mMessage != null)
						mUIInterface.setMessage(mMessage);
					else
						mUIInterface.setStateFinished();
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			}
		}
	};

	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null)
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
		}

		@Override
		public void write(String address, String state) throws RemoteException {
			requestWrite(address, state);
		}

		@Override
		public void stop() throws RemoteException {
			if (mStartedBT)
				mBtAdapter.disable();
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		onSharedPreferenceChanged(getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE), getString(R.string.key_devices));
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_ON) {
					// if pending...
					if (mStartedBT) {
						if ((mQueueAddress != null) && (mQueueState != null))
							requestWrite(mQueueAddress, mQueueState);
					} else if (!mBtAdapter.isDiscovering())
						mBtAdapter.startDiscovery();
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopThreads();
					if (mUIInterface == null)
						RemoteAuthClientService.this.stopSelf();
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
					// connect if configured
					String address = device.getAddress();
					for (String d : mDevices) {
						String[] parts = RemoteAuthClientUI.parseDeviceString(d);
						if (parts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
							// if queued
							if ((mQueueAddress != null) && mQueueAddress.equals(address) && (mQueueState != null))
								mDeviceFound = address;
							break;
						}
					}
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (mDeviceFound != null) {
					if (mConnectThread == null) {
						mConnectThread = new ConnectThread(mDeviceFound, mQueueState);
						mConnectThread.start();
					} else if (!mConnectThread.isConnected()) {
						mConnectThread.shutdown();
						mConnectThread = new ConnectThread(mDeviceFound, mQueueState);
						mConnectThread.start();
					}
					mDeviceFound = null;
					mQueueAddress = null;
					mQueueState = null;
				}
			} else if (action.equals(ACTION_TOGGLE) && intent.hasExtra(EXTRA_DEVICE_ADDRESS)) {
				String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
				requestWrite(address, Integer.toString(RemoteAuthClientUI.STATE_TOGGLE));
			} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) {
				// create widget
				AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
				SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
				Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
				if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					// check if the widget exists, otherwise add it
					if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME) && intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
						Set<String> newWidgets = new HashSet<String>();
						for (String widget : widgets)
							newWidgets.add(widget);
						String name = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME);
						String address = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS);
						String widgetString = name + " " + Integer.toString(appWidgetId) + " " + address;
						// store the widget
						if (!newWidgets.contains(widgetString))
							newWidgets.add(widgetString);
						SharedPreferences.Editor spe = sp.edit();
						spe.putStringSet(getString(R.string.key_widgets), newWidgets);
						spe.commit();
					}
					appWidgetManager.updateAppWidget(appWidgetId, buildWidget(intent, appWidgetId, widgets));
				} else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)) {
					int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
					for (int appWidgetId : appWidgetIds)
						appWidgetManager.updateAppWidget(appWidgetId, buildWidget(intent, appWidgetId, widgets));
				}
			} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
				int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
				SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
				Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
				Set<String> newWidgets = new HashSet<String>();
				for (String widget : widgets) {
					String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
					if (!widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId)))
						newWidgets.add(widget);
				}
				SharedPreferences.Editor spe = sp.edit();
				spe.putStringSet(getString(R.string.key_widgets), newWidgets);
				spe.commit();
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
		stopThreads();
		if (mStartedBT)
			mBtAdapter.disable();
	}

	private RemoteViews buildWidget(Intent intent, int appWidgetId, Set<String> widgets) {
		RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget);
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME)) {
			views.setTextViewText(R.id.device_name, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME));
		} else {
			String name = getString(R.string.widget_device_name);
			for (String widget : widgets) {
				String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
				if (widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
					name = widgetParts[RemoteAuthClientUI.DEVICE_NAME];
					break;
				}
			}
			views.setTextViewText(R.id.device_name, name);
		}
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE))
			views.setTextViewText(R.id.device_state, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE));
		else
			views.setTextViewText(R.id.device_state, getString(R.string.widget_device_state));
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS))
			views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(this, 0, new Intent(this, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)), 0));
		return views;
	}

	private synchronized void requestWrite(String address, String state) {
		if (mBtAdapter.isEnabled()) {
			if (mConnectThread != null) {
				if (mConnectThread.isConnected(address)) { 
					mConnectThread.write(state);
					return;
				}
				mConnectThread.shutdown();
			}
			// attempt connect
			mConnectThread = new ConnectThread(address, state);
			mConnectThread.start();
		} else {
			mQueueAddress = address;
			mQueueState = state;
			mStartedBT = true;
			mBtAdapter.enable();
		}
	}

	private synchronized void stopThreads() {
		if (mConnectThread != null)
			mConnectThread.shutdown();
	}

	private class ConnectThread extends Thread {
		private BluetoothSocket mSocket = null;
		private InputStream mInStream;
		private OutputStream mOutStream;

		private String mChallenge;
		private MessageDigest mDigest;

		private String mAddress;
		private String mState;

		public ConnectThread(String address, String state) {
			mAddress = address;
			mState = state;
			BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
			BluetoothSocket tmp = null;
			try {
				//				tmp = device.createInsecureRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
				tmp = device.createRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
			} catch (IOException e) {
				Log.e(TAG, e.getMessage());
			}
			mSocket = tmp;
		}

		public void run() {
			if (mSocket == null) {
				mMessage = "failed to get a socket";
				mHandler.post(mRunnable);
			} else {
				if (!mSocket.isConnected()) {
					if (mBtAdapter.isDiscovering())
						mBtAdapter.cancelDiscovery();
					try {
						mSocket.connect();
					} catch (IOException e) {
						mMessage = "failed to connect";
						mHandler.post(mRunnable);
						shutdown();
						return;
					}
				}
				// Get the BluetoothSocket input and output streams
				try {
					mInStream = mSocket.getInputStream();
					mOutStream = mSocket.getOutputStream();
				} catch (IOException e) {
					mMessage = "failed to get streams";
					mHandler.post(mRunnable);
					shutdown();
					return;
				}
				byte[] buffer = new byte[1024];
				int readBytes = -1;
				try {
					readBytes = mInStream.read(buffer);
				} catch (IOException e1) {
					mMessage = "failed to read input stream";
					mHandler.post(mRunnable);
				}
				if (readBytes != -1) {
					// construct a string from the valid bytes in the buffer
					String message = new String(buffer, 0, readBytes);
					// listen for challenge, then process a response
					if ((message.length() > 10) && (message.substring(0, 9).equals("challenge")))
						mChallenge = message.substring(10);
					write(mState);
				}
			}
			shutdown();
		}

		public void write(String state) {
			// get passphrase
			String passphrase = null;
			if (mSocket != null) {
				BluetoothDevice bd = mSocket.getRemoteDevice();
				if (bd != null) {
					String address = bd.getAddress();
					if (address != null) {
						for (String d : mDevices) {
							String[] parts = RemoteAuthClientUI.parseDeviceString(d);
							if (parts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
								if ((passphrase = parts[RemoteAuthClientUI.DEVICE_PASSPHRASE]) != null) {
									String challenge = mChallenge;
									// the challenge is stale after it's used
									mChallenge = null;
									if (challenge != null) {
										if (mDigest == null) {
											try {
												mDigest = MessageDigest.getInstance("SHA-256");
											} catch (NoSuchAlgorithmException e) {
												mMessage = "failed to get message digest instance";
												mHandler.post(mRunnable);
											}
										}
										if (mDigest != null) {
											mDigest.reset();
											try {
												mDigest.update((challenge + passphrase + state).getBytes("UTF-8"));
												String request = new BigInteger(1, mDigest.digest()).toString(16);
												mOutStream.write(request.getBytes());
											} catch (IOException e) {
												mMessage = "failed to write to output stream";
												mHandler.post(mRunnable);
											}
										}
									}
								}
								break;
							}
						}
					}
				}
			}
		}

		public boolean isConnected() {
			return (mSocket != null) && mSocket.isConnected() && (mInStream != null) && (mOutStream != null);
		}

		public boolean isConnected(String address) {
			if ((mSocket != null) && (mInStream != null) && (mOutStream != null)) {
				return address.equals(mAddress);
			} else
				return false;
		}

		public void shutdown() {
			mInStream = null;
			mOutStream = null;
			if (mSocket != null) {
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
				mSocket = null;
			}
			mConnectThread = null;
			mMessage = null;
			mHandler.post(mRunnable);
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(getString(R.string.key_devices))) {
			Set<String> devices = sharedPreferences.getStringSet(getString(R.string.key_devices), null);
			if (devices != null) {
				mDevices = new String[devices.size()];
				int d = 0;
				Iterator<String> iter = devices.iterator();
				while (iter.hasNext())
					mDevices[d++] = iter.next();
			} else
				mDevices = new String[0];
		}
	}

}
