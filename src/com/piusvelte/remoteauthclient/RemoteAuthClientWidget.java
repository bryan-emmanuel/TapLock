package com.piusvelte.remoteauthclient;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RemoteAuthClientWidget extends AppWidgetProvider {
	private static final String TAG = "RemoteAuthClientWidget";

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		Log.d(TAG, "action: " + action);
		if (action.equals(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) {
			context.startService(intent.setClass(context, RemoteAuthClientService.class));
		} else if (action.equals(RemoteAuthClientService.ACTION_TOGGLE)) {
			if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
				// signal service
				context.startService(intent.setClass(context, RemoteAuthClientService.class));
			}
		} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
			context.startService(intent.setClass(context, RemoteAuthClientService.class));
		} else super.onReceive(context, intent);
	}

}
