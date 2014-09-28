package com.android.bluetooth.map;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.obex.ClientOperation;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ObexHelper;
import javax.obex.ObexTransport;
import javax.obex.Operation;
import javax.obex.ResponseCodes;

import com.android.bluetooth.R;

public class BluetoothMasObexClient implements BluetoothMapService.PeriodicTask {
    private static final String TAG = "BluetoothMasObexClient";
    private static final boolean DEBUG = true;
    private static final boolean VERBOSE = true;

    // 128 bit UUID for MAS
    private static final byte[] MAS_TARGET = new byte[] {
             (byte)0xBB, (byte)0x58, (byte)0x2B, (byte)0x40,
             (byte)0x42, (byte)0x0C, (byte)0x11, (byte)0xDB,
             (byte)0xB0, (byte)0xDE, (byte)0x08, (byte)0x00,
             (byte)0x20, (byte)0x0C, (byte)0x9A, (byte)0x66
             };
    
    private static final ParcelUuid[] MAP_UUIDS = {
        BluetoothUuid.MAP,
        BluetoothUuid.MAS,
    };
    
    /* Message types */
    private static final String TYPE_SET_NOTIFICATION_REGISTRATION = "x-bt/MAP-NotificationRegistration";
    private static final String TYPE_GET_FOLDER_LISTING  = "x-obex/folder-listing";
    private static final String TYPE_GET_MESSAGE_LISTING = "x-bt/MAP-msg-listing";
    private static final String TYPE_MESSAGE             = "x-bt/message";
    private static final String TYPE_SET_MESSAGE_STATUS  = "x-bt/messageStatus";
    private static final String TYPE_MESSAGE_UPDATE      = "x-bt/MAP-messageUpdate";
    
    public static final int MSG_CONNECT_MAS = 1;
    public static final int MSG_SET_NOTIFICATION = 2;
    
    private Context mContext;
    private BluetoothAdapter mAdapter;
    private ObexTransport mTransport;
    private ClientSession mClientSession;
    private BluetoothDevice mRemoteDevice;
    private BluetoothSocket mBluetoothSocket;
    private BluetoothMapContentObserver mObserver;
    private boolean mObserverRegistered = false;
    private HeaderSet hsConnect = null;
    private SocketConnectThread mConnectThread = null;
    
    private boolean mConnected = false;
    private volatile boolean mWaitingForRemote;
    private volatile boolean mInterrupted;
   
    public Handler mHandler = null;
    
    public BluetoothMasObexClient(Context context) {
    	if (DEBUG) Log.d(TAG, "new BluetoothMasObexClient");
    	mContext = context;
    	mAdapter = BluetoothAdapter.getDefaultAdapter();
    	
        HandlerThread thread = new HandlerThread("BluetoothMasObexClient");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new MasObexClientHandler(looper);
    	
        mObserver = new BluetoothMapContentObserver(mContext);
        mObserver.init();
    }
    
    private class SocketConnectThread extends Thread {

        private boolean stopped = false;
        
        @Override
        public void run() {
            BluetoothSocket socket;
            BluetoothDevice remotedevice;
            if (mBluetoothSocket == null) {
                if (!initRfcommSocket()) {
                	onDisconnected();
                    return;
                }
            }

            while (!stopped) {
                try {
                	socket = mBluetoothSocket;
                    if(socket == null) {
                        Log.w(TAG, "mBluetoothSocket is null");
                        break;
                    }
                    socket.connect();
                    if (DEBUG) Log.d(TAG, "Connected socket connection...");
                    synchronized (BluetoothMasObexClient.this) {
                        if (mBluetoothSocket == null) {
                            Log.w(TAG, "mBluetoothSocket is null");
                            break;
                        }
                        remotedevice = mBluetoothSocket.getRemoteDevice();
                    }
                    if (remotedevice == null) {
                        Log.i(TAG, "getRemoteDevice() = null");
                        break;
                    }
                    if (!mRemoteDevice.equals(remotedevice)) {
                    	Log.e(TAG, "Impossible, target device is not connected device");
                    }
                    
                    try {
                        if (DEBUG) Log.d(TAG, "outgoing connection accepted by: " + remotedevice.getName());
                        startObexClientSession();
                    } catch (IOException ex) {
                        Log.e(TAG, "catch exception starting obex client session"
                                + ex.toString());
                    }

                    stopped = true; // job done ,close this thread;
                } catch (IOException ex) {
                    stopped=true;
                    if (VERBOSE) Log.v(TAG, "Connect exception: " + ex.toString());
                    onDisconnected();
                }
            }
        }

        void shutdown() {
            stopped = true;
            interrupt();
        }
    }

    private final boolean initRfcommSocket() {
		if (DEBUG) Log.d(TAG, "MasObexClient initRfcommSocket()");
	
		boolean initRfcommSocketOK = false;
		final int CONNECT_RETRY_TIME = 10;
	
		if (getRemoteDevice() == null) {
			return initRfcommSocketOK;
		}
	
		// It's possible that create will fail in some cases. retry for 10 times
		for (int i = 0; (i < CONNECT_RETRY_TIME) && !mInterrupted; i++) {
			initRfcommSocketOK = true;
			try {
				// It is mandatory for MSE to support initiation of bonding and
				// encryption.
				mBluetoothSocket = mRemoteDevice.createRfcommSocketToServiceRecord(BluetoothUuid.MAS.getUuid());
	
			} catch (IOException e) {
				Log.e(TAG, "Error connecting RfcommServerSocket " + e.toString());
				initRfcommSocketOK = false;
				closeConnectionSocket();
			}
			if (!initRfcommSocketOK) {
				// Need to break out of this loop if BT is being turned off.
				if (mAdapter == null)
					break;
				int state = mAdapter.getState();
				if ((state != BluetoothAdapter.STATE_TURNING_ON)
						&& (state != BluetoothAdapter.STATE_ON)) {
					Log.w(TAG, "initRfcommSocket failed as BT is (being) turned off");
					break;
				}
				try {
					if (VERBOSE) Log.v(TAG, "wait 300 ms");
					Thread.sleep(300);
				} catch (InterruptedException e) {
					Log.e(TAG, "socketConnectThread thread was interrupted (3)");
				}
			} else {
				break;
			}
		}
		if (mInterrupted) {
			initRfcommSocketOK = false;
			// close socket to avoid resource leakage
			closeConnectionSocket();
		}
	
		if (initRfcommSocketOK) {
			if (VERBOSE) Log.v(TAG, "Succeed connect to rfcomm server socket ");
	
		} else {
			Log.e(TAG, "Error connect to rfcomm server socket after "
					+ CONNECT_RETRY_TIME + " try");
		}
		return initRfcommSocketOK;
	}

	public void startObexClientSession() throws IOException {
        if (DEBUG) Log.d(TAG, "startObexClientSession");

        mTransport = new BluetoothMapRfcommTransport(mBluetoothSocket);

        try {
            mClientSession = new ClientSession(mTransport);
            mConnected = true;
        } catch (IOException e1) {
            Log.e(TAG, "OBEX session create error " + e1.getMessage());
            onDisconnected();
        }
        if (mConnected && mClientSession != null) {
            mConnected = false;
            HeaderSet hs = new HeaderSet();
            // MAS Header bb582b40-420c-11db-b0de-0800200c9a66
            hs.setHeader(HeaderSet.TARGET, MAS_TARGET);

            synchronized (this) {
                mWaitingForRemote = true;
            }
            try {
                hsConnect = mClientSession.connect(hs);
                if (DEBUG) Log.d(TAG, "OBEX session created");
                
                if (hsConnect.mConnectionID != null) {
                    byte[] connectionID = new byte[4];
                    System.arraycopy(hsConnect.mConnectionID, 0, connectionID, 0, 4);
                    mClientSession.setConnectionID(ObexHelper.convertToLong(connectionID));
                } else {
                    Log.w(TAG, "registerNotification: no connection ID");
                }
                mConnected = true;
                onConnected();
                
            } catch (IOException e) {
                Log.e(TAG, "OBEX session connect error " + e.getMessage());
                onDisconnected();
            }
        }
            synchronized (this) {
                mWaitingForRemote = false;
        }
    }
    
    /*override*/
	public void execute() {
		if (DEBUG) Log.d(TAG, "execute() reconnect");
		connect();
	}

	public void connect() {
		if (DEBUG) Log.d(TAG, "connect MAS");
	    
		// Handler maybe shutdown on previous connection loss
		if (mHandler == null) {
	        HandlerThread thread = new HandlerThread("BluetoothMasObexClient");
	        thread.start();
	        Looper looper = thread.getLooper();
	        mHandler = new MasObexClientHandler(looper);
		}
		
		if (getTargetDevice() == null) {
			Log.w(TAG, "No Target device yet, stop connect");
			onDisconnected();
			return;
		}
		
	    if (mConnectThread == null) {
	    	mConnectThread = new SocketConnectThread();
	    	mConnectThread.setName("BluetoothMasConnectThread");
	    	mConnectThread.start();
	    }
	    
	}

	/**
     * Disconnect the connection to MAS server.
     * Call this when the MAS client requests a de-registration on events.
     */
    public void disconnect() {
        try {
            if (mClientSession != null) {
                mClientSession.disconnect(null);
                if (DEBUG) Log.d(TAG, "OBEX session disconnected");
            }
        } catch (IOException e) {
            Log.w(TAG, "OBEX session disconnect error " + e.getMessage());
        }
        try {
            if (mClientSession != null) {
                if (DEBUG) Log.d(TAG, "OBEX session close mClientSession");
                mClientSession.close();
                mClientSession = null;
                if (DEBUG) Log.d(TAG, "OBEX session closed");
            }
        } catch (IOException e) {
            Log.w(TAG, "OBEX session close error:" + e.getMessage());
        }
        if (mTransport != null) {
            try {
                if (DEBUG) Log.d(TAG, "Close Obex Transport");
                mTransport.close();
                mTransport = null;
                mConnected = false;
                if (DEBUG) Log.d(TAG, "Obex Transport Closed");
            } catch (IOException e) {
                Log.e(TAG, "mTransport.close error: " + e.getMessage());
            }
        }
        
        onDisconnected();
    }
    
    private void onConnected() {
		BluetoothMapService.getBluetoothMapService().unregisterTask(this);
	    
	    // MAS connection established, send registerNotification now
	    mHandler.sendMessage(mHandler.obtainMessage(
	    				MSG_SET_NOTIFICATION, BluetoothMapAppParams.NOTIFICATION_STATUS_YES, 0));
	}

	private void onDisconnected() {
    	if (DEBUG) Log.d(TAG, "onDisconnected");
    	
        closeConnectionSocket();
        
        if (mConnectThread != null) {
            try {
            	mConnectThread.shutdown();
            	mConnectThread.join();
            } catch (InterruptedException ex) {
                Log.w(TAG, "mConnectThread close error " + ex);
            }
            mConnectThread = null;
        }
        
        if(mObserverRegistered) {
            mObserver.unregisterObserver();
            mObserverRegistered = false;
        }
        
    	BluetoothMapService.getBluetoothMapService().registerTask(this);
    }
    
    public boolean isConnected() {
	    return mConnected;
	}

	private final synchronized void closeConnectionSocket() {
	    if (mBluetoothSocket != null) {
	        try {
	        	mBluetoothSocket.close();
	        	mBluetoothSocket = null;
	        } catch (IOException e) {
	            Log.e(TAG, "Close Connection Socket error: " + e.toString());
	        }
	    }
	}
    
    /* override */
    public void close() throws IOException {
    	if (DEBUG) Log.d(TAG, "close()");
        if (mObserver != null) {
            mObserver.deinit();
            mObserver = null;
        }
    	
    }
    
	/**
     * Shutdown the MAS.
     */
    public void shutdown() {
        /* should shutdown handler thread first to make sure
         * handleRegistration won't be called when disconnet
         */
        if (mHandler != null) {
            // Shut down the thread
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
            mHandler = null;
        }
        
        /* Disconnect if connected */
        disconnect();
    }
    
    public Handler getMessageHandler() {
        return mHandler;
    }
    
    public BluetoothMapContentObserver getContentObserver() {
        return mObserver;
    }
    
    private final class MasObexClientHandler extends Handler {
        private MasObexClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
        	if (VERBOSE) Log.v(TAG, "Handler(): got msg=" + msg.what);
            switch (msg.what) {
            case MSG_CONNECT_MAS:
                connect();
                break;
            case MSG_SET_NOTIFICATION:
            	registerNotification(msg.arg1);
            	break;
            default:
                break;
            }
        }
    }
    
    public int registerNotification(int notificationStatus) {
    	if (DEBUG) Log.d(TAG, "registerNotification " + notificationStatus);
    	
    	if(notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_YES) {
            /* Connect if we do not have a connection, and start the content observers providing
             * this thread as Handler.
             */
            if(mObserverRegistered == false) {
                mObserver.registerObserver();
                mObserverRegistered = true;
            }
        }
    	
    	boolean error = false;
    	int responseCode = -1;
    	HeaderSet request;
    	ClientSession clientSession = mClientSession;
    	
        if ((!mConnected) || (clientSession == null)) {
            Log.w(TAG, "registerNotification after disconnect:" + mConnected);
            return responseCode;
        }
    	
        request = new HeaderSet();
        BluetoothMapAppParams appParams = new BluetoothMapAppParams();
    	appParams.setNotificationStatus(notificationStatus);
    	
    	ClientOperation putOperation = null;
    	OutputStream outputStream = null;
    	
    	try {
            request.setHeader(HeaderSet.TYPE, TYPE_SET_NOTIFICATION_REGISTRATION);
            request.setHeader(HeaderSet.NAME, "MAS");
            request.setHeader(HeaderSet.APPLICATION_PARAMETER, appParams.EncodeParams());
            
            try {
                byte[] remote = (byte[])hsConnect.getHeader(HeaderSet.WHO);
                if (remote != null) {
                    if (DEBUG) Log.d(TAG, "registerNotification() TARGET: remote=" + Arrays.toString(remote));
                    request.setHeader(HeaderSet.TARGET, remote);
                }
            } catch (IOException e) {
                Log.e(TAG, e.toString());
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }

            if (hsConnect.mConnectionID != null) {
                request.mConnectionID = new byte[4];
                System.arraycopy(hsConnect.mConnectionID, 0, request.mConnectionID, 0, 4);
            } else {
                Log.w(TAG, "registerNotification: no connection ID");
            }
			
            synchronized (this) {
                mWaitingForRemote = true;
            }
            // Send the header first and then the body
            try {
                putOperation = (ClientOperation)clientSession.put(request);
                // TODO - Should this be kept or Removed

            } catch (IOException e) {
                Log.e(TAG, "Error when put HeaderSet " + e.getMessage());
                error = true;
            }
            synchronized (this) {
                mWaitingForRemote = false;
            }
            if (!error) {
                try {
                    if (VERBOSE) Log.v(TAG, "Send headerset Event ");
                    outputStream = putOperation.openOutputStream();
                } catch (IOException e) {
                    Log.e(TAG, "Error when opening OutputStream " + e.getMessage());
                    error = true;
                }
            }
            
            
        } catch (IOException e) {
            Log.w(TAG,"registerNotification: IOException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } catch (IllegalArgumentException e) {
            Log.w(TAG,"registerNotification: IllegalArgumentException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
            
            try {
                if (putOperation != null) {
                    responseCode = putOperation.getResponseCode();
                    if (responseCode != -1) {
                        if (VERBOSE) Log.v(TAG, "Put response code " + responseCode);
                        if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                            Log.i(TAG, "Response error code is " + responseCode);
                        }
                    }
                }
                if (putOperation != null) {
                    putOperation.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
        }
    	
    	return responseCode;
    }
    
    public int GetMessage(String handle) {
    	if(DEBUG) Log.d(TAG, "GetMessage");
    	
    	boolean error = false;
    	int responseCode = -1;
    	
    	HeaderSet request = new HeaderSet();
    	BluetoothMapAppParams appParams = new BluetoothMapAppParams();
    	appParams.setCharset(BluetoothMapAppParams.CHARSET_UTF8);
    	
    	Operation getOperation = null;
    	InputStream bMsgStream = null;
    	
    	try {
        	request.setHeader(HeaderSet.TYPE, TYPE_MESSAGE);
        	request.setHeader(HeaderSet.NAME, handle);
			request.setHeader(HeaderSet.APPLICATION_PARAMETER, appParams.EncodeParams());
			
	    	try {
	    		getOperation = mClientSession.get(request);
			} catch (IOException e) {
				error = true;
				Log.e(TAG, "mClientSession get error: " + e.getMessage());
				return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
			}
	    	
	    	/*  - Read out the message
	         *  - Decode into a bMessage
	         *  - pass it to Database.
	         */

	        try {
	            BluetoothMapbMessage message;
				bMsgStream = getOperation.openInputStream();
				message = BluetoothMapbMessage.parse(bMsgStream, appParams.getCharset()); // Decode the messageBody
				if(VERBOSE) Log.i(TAG, "Message recieved: " + message.toString());
				
				long pushedhandle = mObserver.pushMessage(message, "inbox", appParams);

	        } catch (IllegalArgumentException e) {
	        	error = true;
	            if(DEBUG) Log.w(TAG, "Wrongly formatted bMessage received", e);
	            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
	        } catch (Exception e) {
	        	error = true;
	            // TODO: Change to IOException after debug
	            Log.e(TAG, "Exception occured: ", e);
	            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
	        }
			
        } catch (IOException e) {
        	error = true;
            Log.w(TAG,"GetMessage: IOException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } catch (IllegalArgumentException e) {
        	error = true;
            Log.w(TAG,"GetMessage: IllegalArgumentException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } finally {
            try {
                if (bMsgStream != null) {
                	bMsgStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
            try {
                if ((!error) && (getOperation != null)) {
                    responseCode = getOperation.getResponseCode();
                    if (responseCode != -1) {
                        if (VERBOSE) Log.v(TAG, "Get response code " + responseCode);
                        if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                            Log.i(TAG, "Response error code is " + responseCode);
                        }
                    }
                }
                if (getOperation != null) {
                	getOperation.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
        }
    	
    	return ResponseCodes.OBEX_HTTP_OK;
    }
    
    private BluetoothDevice getTargetDevice() {
    	// TODO target device should be passed by Settings
    	List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        if (DEBUG) Log.i(TAG, "getTargetDevice() BondedDevices size: " + bondedDevices.size());
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                for(ParcelUuid uuid : featureUuids) {
                	if (VERBOSE) Log.v(TAG, "Device: " + device.getAddress() + " UUID: " + uuid.toString());
                }
                if (!BluetoothUuid.containsAnyUuid(featureUuids, MAP_UUIDS)) {
                    continue;
                }
                if (DEBUG) Log.i(TAG, "Bonded MAS: " + device.getAddress());
                deviceList.add(device);
            }
        }
        
        if (!deviceList.isEmpty()) {
        	mRemoteDevice = deviceList.get(0);
        } else {
        	Log.w(TAG, "No bonded MAS device");
        }
        
        return mRemoteDevice;
    }
    
    private BluetoothDevice getRemoteDevice() {
        return mRemoteDevice;
    }
    
    private final void logHeader(HeaderSet hs) {
    	if (VERBOSE) BluetoothMapUtils.logHeader(hs);
    }
}