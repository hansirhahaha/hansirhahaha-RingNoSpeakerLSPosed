package com.hansir.ringnospeaker;

import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

public class RingNoSpeaker implements IXposedHookLoadPackage {
    private static final String TAG = "RingNoSpeaker";
    private static final int USAGE_NOTIFICATION = 5;
    private static final int USAGE_NOTIFICATION_RINGTONE = 6;
    private static final int DEVICE_ROLE_DISABLED = 2;
    private static final int DEVICE_OUT_SPEAKER = 2;
    private static final int DEVICE_OUT_SPEAKER_SAFE = 0x40000;
    private static final Set<String> installed = Collections.synchronizedSet(new HashSet<String>());
    private static Handler handler;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"android".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": loaded in android/system_server");
        final ClassLoader cl = lpparam.classLoader;

        Class<?> inv = findClass("com.android.server.audio.AudioDeviceInventory", cl);
        if (inv == null) {
            XposedBridge.log(TAG + ": AudioDeviceInventory not found");
            return;
        }

        hookAfter(inv, "applyConnectedDevicesRoles");
        hookAfter(inv, "reapplyExternalDevicesRoles");
        hookAfter(inv, "onSetBtActiveDevice");
        hookAfter(inv, "onSetWiredDeviceConnectionState");
        hookAfter(inv, "handleDeviceConnection");
        hookAfter(inv, "removeTrackedConnectedDevice");
        hookAfter(inv, "trackConnectedDevice");

        handler = new Handler(Looper.getMainLooper());
        XposedBridge.log(TAG + ": hooks installed");
    }

    private static void hookAfter(Class<?> c, String name) {
        try {
            XposedBridge.hookAllMethods(c, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    schedule(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": hooked " + name);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": skip " + name + ": " + t);
        }
    }

    private static void schedule(final Object inventory) {
        if (handler == null || inventory == null) return;
        handler.removeCallbacksAndMessages(inventory);
        Runnable r = new Runnable() {
            @Override public void run() { reconcile(inventory); }
        };
        handler.postDelayed(r, 250);
    }

    private static void reconcile(Object inventory) {
        try {
            List<?> strategies = getStrategies(inventory);
            if (strategies == null || strategies.isEmpty()) {
                XposedBridge.log(TAG + ": no strategies");
                return;
            }
            boolean external = hasExternalOutput(inventory);
            Set<String> wanted = new HashSet<String>();
            for (Object strategy : strategies) {
                Integer id = asInt(call(strategy, "getId"));
                if (id == null) continue;
                if (!supports(strategy, USAGE_NOTIFICATION) &&
                    !supports(strategy, USAGE_NOTIFICATION_RINGTONE)) continue;

                for (int type : new int[]{DEVICE_OUT_SPEAKER, DEVICE_OUT_SPEAKER_SAFE}) {
                    Object dev = makeAudioDeviceAttributes(type);
                    if (dev == null) continue;
                    String key = id + ":" + type;
                    if (external) {
                        if (invokeRole(inventory, "addDevicesRoleForStrategy", id, dev)) {
                            installed.add(key);
                            wanted.add(key);
                            XposedBridge.log(TAG + ": disabled speaker for strategy=" + id + " type=" + type);
                        }
                    } else if (installed.contains(key)) {
                        invokeRole(inventory, "removeDevicesRoleForStrategy", id, dev);
                    }
                }
            }
            if (!external) {
                installed.retainAll(wanted);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": reconcile error " + t);
        }
    }

    private static List<?> getStrategies(Object inventory) throws Exception {
        Object x = field(inventory, "mStrategies");
        if (x instanceof List) return (List<?>) x;
        return null;
    }

    private static boolean hasExternalOutput(Object inventory) {
        try {
            Object map = field(inventory, "mConnectedDevices");
            if (!(map instanceof Map)) return false;
            for (Object value : ((Map<?, ?>) map).values()) {
                int type = intField(value, "mDeviceType", -1);
                if (isExternalOutput(type)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isExternalOutput(int type) {
        if (type == DEVICE_OUT_SPEAKER || type == DEVICE_OUT_SPEAKER_SAFE) return false;
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            Method m = as.getDeclaredMethod("isBluetoothOutDevice", int.class);
            m.setAccessible(true);
            Object r = m.invoke(null, type);
            if (r instanceof Boolean && (Boolean) r) return true;
        } catch (Throwable ignored) {}

        String[] names = new String[] {
            "DEVICE_OUT_WIRED_HEADSET",
            "DEVICE_OUT_WIRED_HEADPHONE",
            "DEVICE_OUT_LINE",
            "DEVICE_OUT_USB_HEADSET",
            "DEVICE_OUT_USB_DEVICE",
            "DEVICE_OUT_BLUETOOTH_A2DP",
            "DEVICE_OUT_BLUETOOTH_SCO",
            "DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES",
            "DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER",
            "DEVICE_OUT_HEARING_AID",
            "DEVICE_OUT_BLE_HEADSET",
            "DEVICE_OUT_BLE_SPEAKER",
            "DEVICE_OUT_BLE_BROADCAST"
        };
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            for (String n : names) {
                try {
                    Field f = as.getDeclaredField(n);
                    f.setAccessible(true);
                    if (f.getInt(null) == type) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean supports(Object strategy, int usage) {
        try {
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(usage).build();
            Object r = call(strategy, "supportsAudioAttributes", new Class[]{AudioAttributes.class}, aa);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object makeAudioDeviceAttributes(int nativeType) {
        try {
            Class<?> c = Class.forName("android.media.AudioDeviceAttributes");

            // Current Android framework has an internal-device constructor:
            // AudioDeviceAttributes(int nativeType, String address).
            try {
                Constructor<?> ctor = c.getConstructor(int.class, String.class);
                return ctor.newInstance(nativeType, "");
            } catch (Throwable ignored) {}

            // Fallback for older framework variants: role + public device type.
            if (nativeType == DEVICE_OUT_SPEAKER) {
                for (Constructor<?> ctor : c.getConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 3 && p[0] == int.class && p[1] == int.class
                            && p[2] == String.class) {
                        return ctor.newInstance(2, 2, "");
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": device attr error " + t);
        }
        return null;
    }

    private static boolean invokeRole(Object inventory, String method, int strategy, Object device) {
        try {
            Method target = null;
            for (Method m : inventory.getClass().getDeclaredMethods()) {
                if (!m.getName().equals(method)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == int.class && p[1] == int.class && List.class.isAssignableFrom(p[2].isPrimitive() ? Object.class : p[2]) && p[3] == boolean.class) {
                    target = m; break;
                }
                if (p.length == 3 && p[0] == int.class && p[1] == int.class && List.class.isAssignableFrom(p[2])) {
                    target = m; break;
                }
            }
            if (target == null) {
                for (Class<?> k = inventory.getClass(); k != null && target == null; k = k.getSuperclass()) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (!m.getName().equals(method)) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 4 && p[0] == int.class && p[1] == int.class && List.class.isAssignableFrom(p[2]) && p[3] == boolean.class) { target=m; break; }
                        if (p.length == 3 && p[0] == int.class && p[1] == int.class && List.class.isAssignableFrom(p[2])) { target=m; break; }
                    }
                }
            }
            if (target == null) return false;
            target.setAccessible(true);
            List<Object> devices = new ArrayList<Object>();
            devices.add(device);
            Object result;
            if (target.getParameterTypes().length == 4)
                result = target.invoke(inventory, strategy, DEVICE_ROLE_DISABLED, devices, false);
            else
                result = target.invoke(inventory, strategy, DEVICE_ROLE_DISABLED, devices);
            return !(result instanceof Integer) || ((Integer) result) == 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": role " + method + " failed: " + t);
            return false;
        }
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static int intField(Object o, String name, int def) {
        try {
            Object v = field(o, name);
            return v instanceof Integer ? (Integer) v : def;
        } catch (Throwable t) { return def; }
    }

    private static Object call(Object o, String name) throws Exception {
        return call(o, name, new Class<?>[0]);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(o, args);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        try { return Class.forName(name, false, cl); }
        catch (Throwable t) { return null; }
    }

    private static Integer asInt(Object o) {
        return o instanceof Integer ? (Integer) o : null;
    }
}
