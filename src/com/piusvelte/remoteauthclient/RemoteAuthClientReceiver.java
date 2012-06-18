package com.piusvelte.remoteauthclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RemoteAuthClientReceiver extends BroadcastReceiver {
	
	private static final String TAG = "RemoteAuthClientReceiver";

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		Log.d(TAG, "action: " + action);
		context.startService(intent.setClass(context, RemoteAuthClientService.class));
	}

}
