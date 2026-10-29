package com.ivantrj.glasslauncher;

import android.hardware.input.InputManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The remote's key presser. Not part of the app at runtime: Glass starts it once per boot
 * through the TV's own ADB as the shell user, which may inject input (an app may not):
 *   CLASSPATH=<glass.apk> setsid app_process / com.ivantrj.glasslauncher.Injector <secret>
 * It stays running and presses keys in a few milliseconds, where `input keyevent` takes
 * almost a second per press. It listens on 127.0.0.1 only, and a client must send the
 * secret as its first line. Then one command per line:
 *   k <keycode>   press a key        t <text>   type text        p   ping (answers "ok")
 */
public final class Injector {

    static final int PORT = 47011;

    private static Method inject;
    private static Object inputManager;

    public static void main(String[] args) throws Exception {
        String secret = args.length > 0 ? args[0] : "";
        Looper.prepareMainLooper();
        inputManager = InputManager.class.getDeclaredMethod("getInstance").invoke(null);
        inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                Socket s = server.accept();
                // Each client on its own thread: an idle or half-dead connection (Glass's service
                // restarting) must never hold up the next one, or every key in apps goes nowhere
                Thread t = new Thread(() -> {
                    try (Socket c = s) {
                        serve(c, secret);
                    } catch (Exception ignored) { // a dropped client just ends
                    }
                }, "glass-key-client");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void serve(Socket s, String secret) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = s.getOutputStream();
        if (secret.isEmpty() || !secret.equals(in.readLine())) return;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("k ")) {
                key(Integer.parseInt(line.substring(2).trim()));
            } else if (line.startsWith("t ")) {
                type(line.substring(2));
            } else if (line.equals("p")) {
                out.write("ok\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static void key(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        send(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void type(String text) throws Exception {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) return;
        for (KeyEvent e : events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
    }

    private static void send(KeyEvent e) throws Exception {
        inject.invoke(inputManager, e, 0); // 0 = async: don't wait for the app to handle it
    }

    private Injector() {}
}
