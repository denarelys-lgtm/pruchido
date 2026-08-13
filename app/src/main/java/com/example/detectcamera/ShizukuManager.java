package com.example.detectcamera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class ShizukuManager {

    private static final String TAG = "ShizukuManager";
    public static final int SHIZUKU_CODE = 1002;

    public static boolean estaDisponible() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean tienePermisos() {
        if (!estaDisponible()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void solicitarPermiso() {
        if (estaDisponible() && !tienePermisos()) {
            Shizuku.requestPermission(SHIZUKU_CODE);
        }
    }

    public static void iniciarDaemonAuto(Context context) {
        if (!tienePermisos()) {
            Log.w(TAG, "No hay permiso de Shizuku para arrancar el Daemon.");
            return;
        }

        new Thread(() -> {
            try {
                // Comando Shell idéntico al ejecutado en Bugjaeger
                String comando = "cp /sdcard/ScreenDaemon.jar /data/local/tmp/ScreenDaemon.jar && " +
                        "nohup app_process -Djava.class.path=/data/local/tmp/ScreenDaemon.jar /data/local/tmp ScreenDaemon > /dev/null 2>&1 &";

                Process process = Shizuku.newProcess(new String[]{"sh", "-c", comando}, null, null);
                process.waitFor();

                Log.i(TAG, "Daemon iniciado con éxito a través de Shizuku");
            } catch (Exception e) {
                Log.e(TAG, "Error iniciando Daemon vía Shizuku", e);
            }
        }).start();
    }
}
