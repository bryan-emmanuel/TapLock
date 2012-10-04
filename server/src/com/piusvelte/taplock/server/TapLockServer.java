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
package com.piusvelte.taplock.server;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.Scanner;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.apache.commons.daemon.DaemonInitException;

public class TapLockServer implements Daemon {

	public static final String ACTION_TOGGLE = "com.piusvelte.taplock.ACTION_TOGGLE";
	public static final String ACTION_UNLOCK = "com.piusvelte.taplock.ACTION_UNLOCK";
	public static final String ACTION_LOCK = "com.piusvelte.taplock.ACTION_LOCK";
	public static final String ACTION_PASSPHRASE = "com.piusvelte.taplock.ACTION_PASSPHRASE";
	public static final String PARAM_ACTION = "action";
	public static final String PARAM_HMAC = "hmac";
	public static final String PARAM_PASSPHRASE = "passphrase";
	public static final String PARAM_CHALLENGE = "challenge";
	public static final String PARAM_ERROR = "error";

	protected static final int STATE_LOCKED = 1;
	protected static final int STATE_UNLOCKED = 0;

	protected static final String sPassphraseKey = "passphrase";
	protected static final String sPropertiesKey = "properties";
	protected static final String sLogKey = "log";
	protected static String sPassphrase = "TapLock";
	protected static String sProperties = "taplock.properties";
	protected static String sLog = "taplock.log";
	protected static FileHandler sLogFileHandler;
	protected static Logger sLogger;

	private static ConnectionThread sConnectionThread = null;
	private static int[] sConnectionThreadLock = new int[0];

	protected static final int OS_NIX = 0;
	protected static final int OS_WIN = 1;

	protected static final int OS;

	static {
		OS = System.getProperty("os.name").startsWith("Windows") ? OS_WIN : OS_NIX;
	}

	public static void main(String[] args) {
		String cmd;
		if (args.length > 0) {
			cmd = args[0];
			if (!"start".equals(cmd) || !"stop".equals(cmd))
				cmd = "start";
		} else
			cmd = "start";

		for (String arg : args) {
			int eqIdx = arg.indexOf("=");
			if (eqIdx != -1) {
				String key = arg.substring(0, eqIdx++);
				String value = arg.substring(eqIdx);
				if (sPropertiesKey.equals(key))
					sProperties = value;
				else if (sLogKey.equals(key))
					sLog = value;
			}
		}

		if ("start".equals(cmd)) {
			initialize();
			Scanner sc = new Scanner(System.in);
			System.out.printf("Enter 'stop' to halt: ");
			while(!sc.nextLine().equals("stop") && !isShutdown());
			shutdown();
		} else
			shutdown();


	}

	private static void initialize() {

		try {
			sLogFileHandler = new FileHandler(sLog);
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		Properties prop = new Properties();

		try {
			prop.load(new FileInputStream(sProperties));
			if (prop.isEmpty()) {
				prop.setProperty(sPassphraseKey, sPassphrase);
				prop.store(new FileOutputStream(sProperties), null);
			} else
				sPassphrase = prop.getProperty(sPassphraseKey);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (sLogFileHandler != null) {
			sLogger = Logger.getLogger("TapLock");
			sLogger.setUseParentHandlers(false);
			sLogger.addHandler(sLogFileHandler);
			SimpleFormatter sf = new SimpleFormatter();
			sLogFileHandler.setFormatter(sf);
			writeLog("service starting");
		}

		synchronized (sConnectionThreadLock) {
			(sConnectionThread = new ConnectionThread()).start();
		}

	}

	protected static void setPassphrase(String passphrase) {
		sPassphrase = passphrase;
		Properties prop = new Properties();

		try {
			prop.load(new FileInputStream(sProperties));
			prop.setProperty(sPassphraseKey, sPassphrase);
			prop.store(new FileOutputStream(sProperties), null);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	protected static String getToggleAction() {
		String command = null;
		if (TapLockServer.OS == TapLockServer.OS_NIX)
			command = "gnome-screensaver-command -q";
		else
			command = "rundll32.exe user32.dll, UnLockWorkStation";
		writeLog("getState:" + command);
		if (command != null) {
			Process p = null;
			try {
				p = Runtime.getRuntime().exec(command);
			} catch (IOException e) {
				System.out.println(e.toString());
			}
			if (p != null) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream())); 
				String line = null;
				try {
					line = reader.readLine();
				} catch (IOException e) {
					System.out.println(e.toString());
				} 
				while(line != null) 
				{ 
					System.out.println("command result: " + line);
					if ((TapLockServer.OS == TapLockServer.OS_NIX) && line.contains("inactive"))
						return ACTION_LOCK;
					else if ((TapLockServer.OS == TapLockServer.OS_NIX))
						return ACTION_UNLOCK;
					try {
						line = reader.readLine();
					} catch (IOException e) {
						System.out.println(e.toString());
					} 
				}
				return ACTION_UNLOCK;
			}
		}
		return ACTION_LOCK;
	}

	protected static void writeLog(String message) {
		System.out.println(message);
		if ((sLogFileHandler != null) && (sLogger != null)) {
			sLogger.info(message);
		}
	}

	protected static String getHashString(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(str.getBytes("UTF-8"));
		StringBuffer hexString = new StringBuffer();
		byte[] hash = md.digest();
		for (byte b : hash) {
			if ((0xFF & b) < 0x10)
				hexString.append("0" + Integer.toHexString((0xFF & b)));
			else
				hexString.append(Integer.toHexString(0xFF & b));
		}
		return hexString.toString();
	}

	public static void start(String[] args) {
		main(args);
	}

	public static void stop(String[] args) {
		shutdown();
	}

	@Override
	public void destroy() {
	}

	@Override
	public void init(DaemonContext arg0) throws DaemonInitException, Exception {
	}

	@Override
	public void start() throws Exception {
		initialize();
	}

	@Override
	public void stop() throws Exception {
		shutdown();
	}

	private static boolean isShutdown() {
		synchronized (sConnectionThreadLock) {
			return (sConnectionThread == null);
		}
	}

	public static void shutdown() {
		synchronized (sConnectionThreadLock) {
			if (sConnectionThread != null) {
				sConnectionThread.shutdown();
				sConnectionThread = null;
			}
		}
	}
}
