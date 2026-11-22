package com.autocamera.app;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 透過 Camera2 API 讀取手機每顆鏡頭的焦距、感光元件尺寸、光圈與變焦範圍。 */
@CapacitorPlugin(name = "CameraInfo")
public class CameraInfoPlugin extends Plugin {

    @PluginMethod
    public void getCameras(PluginCall call) {
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            JSArray out = new JSArray();
            Set<String> seen = new HashSet<>();
            List<String> ids = new ArrayList<>(Arrays.asList(cm.getCameraIdList()));
            int listed = ids.size();
            // 有些廠商的鏡頭不在公開清單裡，但仍可用 ID 查詢
            for (int i = 0; i < 20; i++) {
                String s = String.valueOf(i);
                if (!ids.contains(s)) ids.add(s);
            }
            for (int i = 0; i < ids.size(); i++) {
                addCamera(cm, ids.get(i), null, i >= listed, out, seen);
            }
            JSObject ret = new JSObject();
            ret.put("cameras", out);
            ret.put("model", Build.MODEL);
            ret.put("manufacturer", Build.MANUFACTURER);
            ret.put("device", Build.DEVICE);
            ret.put("sdk", Build.VERSION.SDK_INT);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("無法讀取相機資訊：" + e.getMessage());
        }
    }

    private void addCamera(CameraManager cm, String id, String parent, boolean probed, JSArray out, Set<String> seen) {
        if (seen.contains(id)) return;
        CameraCharacteristics c;
        try {
            c = cm.getCameraCharacteristics(id);
        } catch (Exception e) {
            return;
        }
        seen.add(id);
        JSObject o = new JSObject();
        o.put("id", id);
        if (parent != null) o.put("parent", parent);
        o.put("probed", probed);

        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        o.put("facing", facing == null ? "unknown"
                : facing == CameraCharacteristics.LENS_FACING_BACK ? "back"
                : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external");

        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        o.put("focalLengths", toArr(fl));
        float[] ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        o.put("apertures", toArr(ap));
        SizeF sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (sz != null) {
            o.put("sensorW", sz.getWidth());
            o.put("sensorH", sz.getHeight());
        }
        Size px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (px != null) {
            o.put("pixelW", px.getWidth());
            o.put("pixelH", px.getHeight());
        }
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocus != null) o.put("minFocusDiopter", minFocus);

        boolean logical = false;
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null && Build.VERSION.SDK_INT >= 28) {
            for (int cap : caps) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) logical = true;
            }
        }
        o.put("logical", logical);

        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) {
                o.put("zoomMin", zr.getLower());
                o.put("zoomMax", zr.getUpper());
            }
        }
        out.put(o);

        if (logical && Build.VERSION.SDK_INT >= 28) {
            for (String pid : c.getPhysicalCameraIds()) {
                addCamera(cm, pid, id, false, out, seen);
            }
        }
    }

    private JSArray toArr(float[] a) {
        JSArray r = new JSArray();
        if (a == null) return r;
        for (float v : a) {
            try { r.put((double) v); } catch (Exception ignored) {}
        }
        return r;
    }
}
