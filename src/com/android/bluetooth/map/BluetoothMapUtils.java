/*
* Copyright (C) 2013 Samsung System LSI
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.bluetooth.map;

import java.io.IOException;
import java.util.Arrays;

import javax.obex.HeaderSet;
import javax.obex.ObexHelper;

import android.util.Log;


/**
 * Various utility methods and generic defines that can be used throughout MAPS
 */
public class BluetoothMapUtils {

    private static final String TAG = "MapUtils";
    private static final boolean V = BluetoothMapService.VERBOSE;
    /* We use the upper 5 bits for the type mask - avoid using the top bit, since it
     * indicates a negative value, hence corrupting the formatter when converting to
     * type String. (I really miss the unsigned type in Java:))
     */
    private static final long HANDLE_TYPE_MASK            = 0xf<<59;
    private static final long HANDLE_TYPE_MMS_MASK        = 0x1<<59;
    private static final long HANDLE_TYPE_EMAIL_MASK      = 0x2<<59;
    private static final long HANDLE_TYPE_SMS_GSM_MASK    = 0x4<<59;
    private static final long HANDLE_TYPE_SMS_CDMA_MASK   = 0x8<<59;

    /**
     * This enum is used to convert from the bMessage type property to a type safe
     * type. Hence do not change the names of the enum values.
     */
    public enum TYPE{
        EMAIL,
        SMS_GSM,
        SMS_CDMA,
        MMS
    }

    /**
     * Convert a Content Provider handle and a Messagetype into a unique handle
     * @param cpHandle content provider handle
     * @param messageType message type (TYPE_MMS/TYPE_SMS_GSM/TYPE_SMS_CDMA/TYPE_EMAIL)
     * @return String Formatted Map Handle
     */
    static public String getMapHandle(long cpHandle, TYPE messageType){
        String mapHandle = "-1";
        switch(messageType)
        {
            case MMS:
                mapHandle = String.format("%016X",(cpHandle | HANDLE_TYPE_MMS_MASK));
                break;
            case SMS_GSM:
                mapHandle = String.format("%016X",cpHandle | HANDLE_TYPE_SMS_GSM_MASK);
                break;
            case SMS_CDMA:
                mapHandle = String.format("%016X",cpHandle | HANDLE_TYPE_SMS_CDMA_MASK);
                break;
            case EMAIL:
                mapHandle = String.format("%016X",(cpHandle | HANDLE_TYPE_EMAIL_MASK)); //TODO correct when email support is implemented
                break;
                default:
                    throw new IllegalArgumentException("Message type not supported");
        }
        return mapHandle;

    }

    /**
     * Convert a handle string the the raw long representation, including the type bit.
     * @param mapHandle the handle string
     * @return the handle value
     */
    static public long getMsgHandleAsLong(String mapHandle){
        return Long.parseLong(mapHandle, 16);
    }
    /**
     * Convert a Map Handle into a content provider Handle
     * @param mapHandle handle to convert from
     * @return content provider handle without message type mask
     */
    static public long getCpHandle(String mapHandle)
    {
        long cpHandle = getMsgHandleAsLong(mapHandle);
        /* remove masks as the call should already know what type of message this handle is for */
        cpHandle &= ~HANDLE_TYPE_MASK;
        return cpHandle;
    }

    /**
     * Extract the message type from the handle.
     * @param mapHandle
     * @return
     */
    static public TYPE getMsgTypeFromHandle(String mapHandle) {
        long cpHandle = getMsgHandleAsLong(mapHandle);

        if((cpHandle & HANDLE_TYPE_MMS_MASK) != 0)
            return TYPE.MMS;
        if((cpHandle & HANDLE_TYPE_EMAIL_MASK) != 0)
            return TYPE.EMAIL;
        if((cpHandle & HANDLE_TYPE_SMS_GSM_MASK) != 0)
            return TYPE.SMS_GSM;
        if((cpHandle & HANDLE_TYPE_SMS_CDMA_MASK) != 0)
            return TYPE.SMS_CDMA;

        throw new IllegalArgumentException("Message type not found in handle string.");
    }

	static final void logHeader(HeaderSet hs) {
		Log.i(TAG, "Dumping HeaderSet " + hs.toString());
		try {
			if (hs.getHeader(HeaderSet.CONNECTION_ID) == null) {
				Log.i(TAG, "CONNECTION_ID : " + hs.getHeader(HeaderSet.CONNECTION_ID));
			} else {
				Log.i(TAG, "CONNECTION_ID : " + ObexHelper.convertToLong(((byte[])hs.getHeader(HeaderSet.CONNECTION_ID))));
			}
			Log.i(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
			Log.i(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
			if (hs.getHeader(HeaderSet.TARGET) == null) {
				Log.i(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
			} else {
				Log.i(TAG, "TARGET : " + Arrays.toString(((byte[])hs.getHeader(HeaderSet.TARGET))));
			}
			if (hs.getHeader(HeaderSet.WHO) == null) {
				Log.i(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
			} else {
				Log.i(TAG, "WHO : " + Arrays.toString(((byte[])hs.getHeader(HeaderSet.WHO))));
			}
			if (hs.getHeader(HeaderSet.APPLICATION_PARAMETER) == null) {
				Log.i(TAG, "APPLICATION_PARAMETER : " + hs.getHeader(HeaderSet.APPLICATION_PARAMETER));
			} else {
				Log.i(TAG, "APPLICATION_PARAMETER : " + Arrays.toString(((byte[])hs.getHeader(HeaderSet.APPLICATION_PARAMETER))));
			}
		} catch (IOException e) {
            Log.e(TAG, "dump HeaderSet error " + e);
        }
        Log.i(TAG, "NEW!!! Dumping HeaderSet END");
    }
}

