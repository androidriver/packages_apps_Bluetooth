package com.android.bluetooth.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import javax.obex.HeaderSet;
import javax.obex.ObexHelper;
import javax.obex.Operation;
import javax.obex.ResponseCodes;
import javax.obex.ServerRequestHandler;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import com.android.bluetooth.map.BluetoothMapUtils;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.internal.util.XmlUtils;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Xml;

public class BluetoothMnsObexServer extends ServerRequestHandler {

    private static final String TAG = "BluetoothMnsObexServer";

    private static final boolean DEBUG = true;
    private static final boolean VERBOSE = true;

    private static final int UUID_LENGTH = 16;

    // 128 bit UUID for MAP
    private static final byte[] MNS_TARGET = new byte[] {
             (byte)0xBB, (byte)0x58, (byte)0x2B, (byte)0x41,
             (byte)0x42, (byte)0x0C, (byte)0x11, (byte)0xDB,
             (byte)0xB0, (byte)0xDE, (byte)0x08, (byte)0x00,
             (byte)0x20, (byte)0x0C, (byte)0x9A, (byte)0x66
             };
	private static final String TYPE_EVENT = "x-bt/MAP-event-report";
	
	private Context mContext;
	private Handler mCallback;
	private BluetoothMasObexClient mMasClient;
	public static boolean sIsAborted = false;
	
    public BluetoothMnsObexServer(Handler callback, Context context, BluetoothMasObexClient masclient) {
    	super();
    	mContext = context;
    	mCallback = callback;
    	mMasClient = masclient;
    }
	
    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        if (DEBUG) Log.d(TAG, "onConnect() enter");
        
        try {
            byte[] uuid = (byte[])request.getHeader(HeaderSet.TARGET);
            if (uuid == null) {
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            if (DEBUG) Log.d(TAG, "onConnect(): uuid=" + Arrays.toString(uuid));

            if (uuid.length != UUID_LENGTH) {
                Log.w(TAG, "Wrong UUID length");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid[i] != MNS_TARGET[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
            reply.setHeader(HeaderSet.WHO, uuid);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        try {
            byte[] remote = (byte[])request.getHeader(HeaderSet.WHO);
            if (remote != null) {
                if (DEBUG) Log.d(TAG, "onConnect(): remote=" + Arrays.toString(remote));
                reply.setHeader(HeaderSet.TARGET, remote);
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothMapService.MNS_SESSION_ESTABLISHED;
        msg.sendToTarget();
        if (VERBOSE) Log.v(TAG, "onConnect(): msg MNS_SESSION_ESTABLISHED sent out.");
        return ResponseCodes.OBEX_HTTP_OK;
    }
    
    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        if (DEBUG) Log.d(TAG, "onDisconnect(): enter");

        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothMapService.MNS_SESSION_DISCONNECTED;
            msg.sendToTarget();
            if (VERBOSE) Log.v(TAG, "onDisconnect(): msg MNS_SESSION_DISCONNECTED sent out.");
        }
    }
    
    @Override
    public int onAbort(HeaderSet request, HeaderSet reply) {
        if (DEBUG) Log.d(TAG, "onAbort(): enter");
        sIsAborted = true;
        return ResponseCodes.OBEX_HTTP_OK;
    }
    
    @Override
    public void onClose() {
    	if (DEBUG) Log.d(TAG, "onClose(): enter");
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothMapService.MNS_SERVERSESSION_CLOSE;
            msg.sendToTarget();
            if (DEBUG) Log.d(TAG, "onClose(): msg MNS_SERVERSESSION_CLOSE sent out.");
        }
    }
    
    @Override
    public int onPut(final Operation op) {
        if (DEBUG) Log.d(TAG, "onPut(): enter");
        HeaderSet request = null;
        String type, name;
        byte[] appParamRaw;
        BluetoothMapAppParams appParams = null;

        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);
            name = (String)request.getHeader(HeaderSet.NAME);
            appParamRaw = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
            if(appParamRaw != null)
                appParams = new BluetoothMapAppParams(appParamRaw);
        } catch (Exception e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        if(DEBUG) Log.d(TAG,"type = " + type + ", name = " + name);
        if (type.equals(TYPE_EVENT)) {
        	
            return handleEvent(op, appParams);
        }

        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }
    
    private int handleEvent(final Operation op, BluetoothMapAppParams appParams) {
    	if (DEBUG) Log.d(TAG, "handleEvent(): enter");
    	String type = null, handle = null, folder = null, old_folder = null, msg_type = null;
    	int masInstanceId;
    	
    	masInstanceId = appParams.getMasInstanceId();
    	if(DEBUG) Log.d(TAG,"handleEvent(): masInstanceId = " + masInstanceId);
    	
    	// Parse Event
    	InputStream eventStream = null;
        try {
        	eventStream = op.openInputStream();
        	
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(eventStream, "UTF-8");
            XmlUtils.beginDocument(parser, "MAP-event-report");
            
            while (true) {
                XmlUtils.nextElement(parser);
                
                String element = parser.getName();
                if (element == null) break;
                
                if (element.equals("event")) {
                    type = parser.getAttributeValue(null, "type");
                    handle = parser.getAttributeValue(null, "handle");
                    folder = parser.getAttributeValue(null, "folder");
                    old_folder = parser.getAttributeValue(null, "old_folder");
                    msg_type = parser.getAttributeValue(null, "msg_type");
                    
                    if (VERBOSE) Log.d(TAG, "type: " + type
                    		 + " handle: " + handle
                    		 + " folder: " + folder
                    		 + " old_folder: " + old_folder
                    		 + " msg_type: " + msg_type);
                    
                } else {
                    Log.v(TAG, "Skipping unknown Event tag " + element);
                }
            }
        } catch (IOException e) {
        	Log.e(TAG, "Unable to read Event", e);
        	return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        } catch (NumberFormatException e) {
        	Log.e(TAG, "Unable to parse Event", e);
        	return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        } catch (XmlPullParserException e) {
        	Log.e(TAG, "Unable to parse Event", e);
        	return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        } finally {
            if (eventStream != null) {
                try {
                	eventStream.close();
                } catch (IOException e) {
                	Log.e(TAG, "Unable to Close", e);
                	return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                }
            }
        }
    	// Parse ended
        
        if (VERBOSE) Log.i(TAG, type);
        if (type.equals("NewMessage")) {
        	
        	mMasClient.GetMessage(handle);
        	
        } else if (type.equals("MessageShift")) {
        	
        } else if (type.equals("DeliverySuccess")) {
        	
        } else if (type.equals("SendingSuccess")) {
        	
        } else if (type.equals("DeliveryFailure")) {
        	
        } else if (type.equals("SendingFailure")) {
        	
        } else if (type.equals("MessageShift")) {
        	
        }

    	return ResponseCodes.OBEX_HTTP_OK;
    }
    
    private final void logHeader(HeaderSet hs) {
    	if (VERBOSE) BluetoothMapUtils.logHeader(hs);
    }
}