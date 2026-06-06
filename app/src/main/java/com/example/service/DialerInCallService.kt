package com.example.service

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import com.example.CallManager

class DialerInCallService : InCallService() {
    companion object {
        private const val TAG = "DialerInCallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "onCallAdded: Native call added -> $call")
        CallManager.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "onCallRemoved: Native call removed -> $call")
        CallManager.onCallRemoved(call)
    }
}
