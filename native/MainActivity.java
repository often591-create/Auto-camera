package com.autocamera.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(CameraInfoPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
