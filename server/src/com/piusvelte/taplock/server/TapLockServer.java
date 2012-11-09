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

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.swing.JOptionPane;

import org.apache.commons.codec.binary.Base64;
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

	protected static final String sPassphraseKey = "passphrase";
	protected static final String sDisplaySystemTrayKey = "displaysystemtray";
	protected static final String sDebuggingKey = "debugging";
	protected static final String sPasswordKey = "password";
	protected static String sPassphrase = "TapLock";
	protected static boolean sDisplaySystemTray = true;
	protected static boolean sDebugging = false;
	protected static FileHandler sLogFileHandler;
	protected static Logger sLogger;

	private static ConnectionThread sConnectionThread = null;
	private static int[] sConnectionThreadLock = new int[0];

	protected static final String S_LOCALHOST = "127.0.0.1";
	protected static final int SERVER_PORT = 1491;
	protected static final int S_USERBUF = 32;
	protected static final int S_PASSBUF = 32;
	protected static final int S_CREDBUF = S_USERBUF + S_PASSBUF;
	protected static final String CREDENTIAL_PROVIDER_SUCCESS = "0";

	protected static final int OS_NIX = 0;
	protected static final int OS_WIN = 1;

	protected static final int OS;
	private static final String APP_PATH;
	protected static final String sProperties;
	protected static final String sLog;
	private static final String sKeystore;

	static {
		OS = System.getProperty("os.name").startsWith("Windows") ? OS_WIN : OS_NIX;
		if (OS == OS_WIN)
			APP_PATH = System.getenv("APPDATA") + "\\Tap Lock Server\\";
		else if (OS == OS_NIX)
			APP_PATH = System.getProperty("user.home") + "/.taplockserver/";
		else {
			String decJarPath = "";
			try {
				decJarPath = URLDecoder.decode(TapLockServer.class.getProtectionDomain().getCodeSource().getLocation().getPath(), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				writeLog("getJarPath: " + e.getMessage());
			}
			int lastPathIdx = decJarPath.lastIndexOf("TapLockServer.jar");
			if (lastPathIdx != -1)
				decJarPath = decJarPath.substring(0, lastPathIdx);
			APP_PATH = decJarPath;
		}
		new File(APP_PATH).mkdir();
		sProperties = APP_PATH + "taplock.properties";
		sLog = APP_PATH + "taplock.log";
		sKeystore = APP_PATH + "taplock.keystore";
	}

	public static void main(String[] args) {
		String cmd;
		if (args.length > 0) {
			cmd = args[0];
			if (!"start".equals(cmd) || !"stop".equals(cmd))
				cmd = "start";
		} else
			cmd = "start";

		if ("start".equals(cmd)) {
			initialize();
			while (!isShutdown());
		} else
			shutdown();
		System.exit(0);
	}

	private static void initialize() {

		try {
			sLogFileHandler = new FileHandler(sLog);
		} catch (SecurityException e) {
			writeLog("sLogFileHandler init: " + e.getMessage());
		} catch (IOException e) {
			writeLog("sLogFileHandler init: " + e.getMessage());
		}

		File propertiesFile = new File(sProperties);
		if (!propertiesFile.exists()) {
			try {
				propertiesFile.createNewFile();
			} catch (IOException e) {
				writeLog("propertiesFile.createNewFile: " + e.getMessage());
			}
		}

		Properties prop = new Properties();

		try {
			prop.load(new FileInputStream(sProperties));
			if (prop.isEmpty()) {
				prop.setProperty(sPassphraseKey, sPassphrase);
				prop.setProperty(sDisplaySystemTrayKey, Boolean.toString(sDisplaySystemTray));
				prop.setProperty(sDebuggingKey, Boolean.toString(sDebugging));
				prop.store(new FileOutputStream(sProperties), null);
			} else {
				if (prop.containsKey(sPassphraseKey))
					sPassphrase = prop.getProperty(sPassphraseKey);
				else
					prop.setProperty(sPassphraseKey, sPassphrase);
				if (prop.containsKey(sDisplaySystemTrayKey))
					sDisplaySystemTray = Boolean.parseBoolean(prop.getProperty(sDisplaySystemTrayKey));
				else
					prop.setProperty(sDisplaySystemTrayKey, Boolean.toString(sDisplaySystemTray));
				if (prop.containsKey(sDebuggingKey))
					sDebugging = Boolean.parseBoolean(prop.getProperty(sDebuggingKey));
				else
					prop.setProperty(sDebuggingKey, Boolean.toString(sDebugging));
			}
		} catch (FileNotFoundException e) {
			writeLog("prop load: " + e.getMessage());
		} catch (IOException e) {
			writeLog("prop load: " + e.getMessage());
		}

		if (sLogFileHandler != null) {
			sLogger = Logger.getLogger("TapLock");
			sLogger.setUseParentHandlers(false);
			sLogger.addHandler(sLogFileHandler);
			SimpleFormatter sf = new SimpleFormatter();
			sLogFileHandler.setFormatter(sf);
			writeLog("service starting");
		}

		if (sDisplaySystemTray && SystemTray.isSupported()) {
			final SystemTray systemTray = SystemTray.getSystemTray();
			Image trayIconImg = Toolkit.getDefaultToolkit().getImage(TapLockServer.class.getResource("/systemtrayicon.png"));
			final TrayIcon trayIcon = new TrayIcon(trayIconImg, "Tap Lock");
			trayIcon.setImageAutoSize(true);
			PopupMenu popupMenu = new PopupMenu();
			MenuItem aboutItem = new MenuItem("About");
			CheckboxMenuItem toggleSystemTrayIcon = new CheckboxMenuItem("Display Icon in System Tray");
			toggleSystemTrayIcon.setState(sDisplaySystemTray);
			CheckboxMenuItem toggleDebugging = new CheckboxMenuItem("Debugging");
			toggleDebugging.setState(sDebugging);
			MenuItem shutdownItem = new MenuItem("Shutdown Tap Lock Server");
			popupMenu.add(aboutItem);
			popupMenu.add(toggleSystemTrayIcon);
			if (OS == OS_WIN) {
				MenuItem setPasswordItem = new MenuItem("Set password");
				popupMenu.add(setPasswordItem);
				setPasswordItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						String password = encryptString((String) JOptionPane.showInputDialog("Enter you Windows account password:"));
						Properties prop = new Properties();
						try {
							prop.load(new FileInputStream(sProperties));
							prop.setProperty(sPasswordKey, password);
							prop.store(new FileOutputStream(sProperties), null);
						} catch (FileNotFoundException e1) {
							writeLog("prop load: " + e1.getMessage());
						} catch (IOException e1) {
							writeLog("prop load: " + e1.getMessage());
						}
					}
				});
			}
			popupMenu.add(toggleDebugging);
			popupMenu.add(shutdownItem);
			trayIcon.setPopupMenu(popupMenu);
			try {
				systemTray.add(trayIcon);
			} catch (AWTException e) {
				writeLog("systemTray.add: " + e.getMessage());
			}
			aboutItem.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					String newline = System.getProperty("line.separator");
					newline += newline;
					JOptionPane.showMessageDialog(null, "Tap Lock" + newline + "Copyright (c) 2012 Bryan Emmanuel" + newline + "This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version." + newline + "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details." + newline + "You should have received a copy of the GNU General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>." + newline + "Bryan Emmanuel piusvelte@gmail.com");
				}
			});
			toggleSystemTrayIcon.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					setTrayIconDisplay(e.getStateChange() == ItemEvent.SELECTED);
					if (!sDisplaySystemTray)
						systemTray.remove(trayIcon);
				}
			});
			toggleDebugging.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(ItemEvent e) {
					setDebugging(e.getStateChange() == ItemEvent.SELECTED);
				}
			});
			shutdownItem.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					shutdown();
				}
			});
		}
		synchronized (sConnectionThreadLock) {
			(sConnectionThread = new ConnectionThread()).start();
		}
	}

	protected static void setTrayIconDisplay(boolean display) {
		sDisplaySystemTray = display;
		Properties prop = new Properties();
		try {
			prop.load(new FileInputStream(sProperties));
			prop.setProperty(sDisplaySystemTrayKey, Boolean.toString(sDisplaySystemTray));
			prop.store(new FileOutputStream(sProperties), null);
		} catch (FileNotFoundException e) {
			writeLog("prop load: " + e.getMessage());
		} catch (IOException e) {
			writeLog("prop load: " + e.getMessage());
		}
	}

	protected static void setDebugging(boolean debugging) {
		if (sDebugging)
			writeLog("debugging stopped");
		sDebugging = debugging;
		if (sDebugging)
			writeLog("debugging started");
		Properties prop = new Properties();
		try {
			prop.load(new FileInputStream(sProperties));
			prop.setProperty(sDebuggingKey, Boolean.toString(sDebugging));
			prop.store(new FileOutputStream(sProperties), null);
		} catch (FileNotFoundException e) {
			writeLog("prop load: " + e.getMessage());
		} catch (IOException e) {
			writeLog("prop load: " + e.getMessage());
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
			writeLog("prop load: " + e.getMessage());
		} catch (IOException e) {
			writeLog("prop load: " + e.getMessage());
		}
	}

	protected static String getToggleAction() {
		String command = null;
		if (OS == OS_NIX) {
			command = "gnome-screensaver-command -q";
			Process p = null;
			try {
				p = Runtime.getRuntime().exec(command);
			} catch (IOException e) {
				writeLog("Runtime.getRuntime().exec: " + e.getMessage());
			}
			if (p != null) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
				String line = null;
				try {
					line = reader.readLine();
				} catch (IOException e) {
					writeLog("reader.readLine: " + e.getMessage());
				}
				while(line != null)
				{
					if (line.contains("inactive"))
						return ACTION_LOCK;
					else
						return ACTION_UNLOCK;
				}
				return ACTION_UNLOCK;
			}
		}
		return ACTION_LOCK;
	}

	protected static void writeLog(String message) {
		if (sDebugging && (sLogFileHandler != null) && (sLogger != null)) {
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

	//	@Override
	public void destroy() {
	}

	//	@Override
	public void init(DaemonContext arg0) throws DaemonInitException, Exception {
	}

	//	@Override
	public void start() throws Exception {
		initialize();
	}

	//	@Override
	public void stop() throws Exception {
		shutdown();
	}

	private static boolean isShutdown() {
		synchronized (sConnectionThreadLock) {
			return (sConnectionThread == null);
		}
	}

	public static void shutdown() {
		System.out.println("shutdown");
		synchronized (sConnectionThreadLock) {
			if (sConnectionThread != null) {
				sConnectionThread.shutdown();
				sConnectionThread = null;
			}
		}
		if (sLogger != null)
			sLogger = null;
		if (sLogFileHandler != null)
			sLogFileHandler.close();
	}
	protected static SecretKey getSecretKey() {
		SecretKey sk = null;
		KeyStore ks = null;
		try {
			ks = KeyStore.getInstance("BKS");
		} catch (KeyStoreException e) {
			writeLog("encryptString: " + e.getMessage());
		}
		if (ks != null) {
			try {
				ks.load(new FileInputStream(sKeystore), sPassphrase.toCharArray());
			} catch (NoSuchAlgorithmException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (CertificateException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (FileNotFoundException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (IOException e) {
				writeLog("encryptString: " + e.getMessage());
			}
			try {
				sk = (SecretKey) ks.getKey(sPassphrase, sPassphrase.toCharArray());
			} catch (UnrecoverableKeyException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (KeyStoreException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (NoSuchAlgorithmException e) {
				writeLog("encryptString: " + e.getMessage());
			}
		}
		return sk;
	}

	protected static String encryptString(String decStr) {
		String encStr = null;
		SecretKey sk = getSecretKey();
		if (sk == null) {
			// create key
			KeyGenerator kgen = null;
			try {
				kgen = KeyGenerator.getInstance("AES");
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
			if (kgen != null) {
				kgen.init(256);
				sk = kgen.generateKey();
				// generate password
				SecureRandom sr = new SecureRandom();
				sPassphrase = new BigInteger(256, sr).toString(8);
				// create a keystore
				KeyStore ks = null;
				try {
					ks = KeyStore.getInstance("BKS");
				} catch (KeyStoreException e) {
					writeLog("encryptString: " + e.getMessage());
				}
				if (ks != null) {
					try {
						ks.load(null, sPassphrase.toCharArray());
						ks.setKeyEntry(sPassphrase, sk, sPassphrase.toCharArray(), null);
						ks.store(new FileOutputStream(sKeystore), sPassphrase.toCharArray());
					} catch (NoSuchAlgorithmException e) {
						writeLog("encryptString: " + e.getMessage());
					} catch (CertificateException e) {
						writeLog("encryptString: " + e.getMessage());
					} catch (IOException e) {
						writeLog("encryptString: " + e.getMessage());
					} catch (KeyStoreException e) {
						writeLog("encryptString: " + e.getMessage());
					}
				}
			}
		}
		if ((sk != null) && (decStr != null)) {
			Cipher cipher;
			try {
				cipher = Cipher.getInstance("AES");
				cipher.init(Cipher.ENCRYPT_MODE, sk);
				return new String(Base64.encodeBase64(cipher.doFinal(decStr.getBytes("UTF-8"))));
			} catch (NoSuchAlgorithmException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (NoSuchPaddingException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (InvalidKeyException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (IllegalBlockSizeException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (BadPaddingException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (UnsupportedEncodingException e) {
				writeLog("encryptString: " + e.getMessage());
			}
		}
		return encStr;
	}

	protected static String decryptString(String encStr) {
		String decStr = null;
		SecretKey sk = getSecretKey();
		if (sk != null) {
			Cipher cipher;
			try {
				cipher = Cipher.getInstance("AES");
				cipher.init(Cipher.DECRYPT_MODE, sk);
				return new String(cipher.doFinal(Base64.decodeBase64(encStr)), "UTF-8");
			} catch (NoSuchAlgorithmException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (NoSuchPaddingException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (InvalidKeyException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (UnsupportedEncodingException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (IllegalBlockSizeException e) {
				writeLog("encryptString: " + e.getMessage());
			} catch (BadPaddingException e) {
				writeLog("encryptString: " + e.getMessage());
			}
		}
		return decStr;
	}
}
