package com.example.detectcamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ScreenSocketClient {

    private static final String TAG = "ScreenSocketClient";
    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9090;

    private final WebServer webServer;
    private volatile boolean running = false;
    private Thread workerThread;

    public ScreenSocketClient(WebServer webServer) {
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        workerThread = new Thread(() -> {
            while (running) {
                try (Socket socket = new Socket(HOST, PUERTO);
                     DataInputStream dis = new DataInputStream(socket.getInputStream())) {

                    Log.i(TAG, "Conectado exitosamente al Daemon ADB en localhost:9090");

                    while (running && !socket.isClosed()) {
                        int length = dis.readInt();
                        if (length > 0 && length < 30_000_000) { // Protección de rango de tamaño
                            byte[] rawBytes = new byte[length];
                            dis.readFully(rawBytes);

                            // Convertir los bytes RAW del Daemon a JPEG escalado a 720p
                            byte[] jpegOptimizado = procesarRawFrameAJpeg(rawBytes);

                            if (jpegOptimizado != null && webServer != null) {
                                webServer.actualizarFramePantalla(jpegOptimizado);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Reintentando conexión con el Daemon en 2 segundos...");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                }
            }
        });

        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (webServer != null) {
            webServer.actualizarFramePantalla(null);
        }
    }

    /**
     * Convierte la captura RAW en formato ARGB de screencap en un JPEG ligero de 720p.
     */
    private byte[] procesarRawFrameAJpeg(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < 12) return null;

        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawFrame);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // El encabezado de screencap en Android es Little Endian

            int width = buffer.getInt();
            int height = buffer.getInt();
            int format = buffer.getInt();

            int pixelDataSize = width * height * 4;
            int headerOffset = rawFrame.length - pixelDataSize;

            Bitmap bitmap = null;

            // Validar si corresponde al buffer RAW nativo de screencap
            if (width > 0 && height > 0 && width <= 4000 && height <= 4000 
                    && headerOffset >= 12 && headerOffset < rawFrame.length) {
                
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                buffer.position(headerOffset);
                bitmap.copyPixelsFromBuffer(buffer);
            } else {
                // Fallback por si los datos entran ya codificados
                bitmap = BitmapFactory.decodeByteArray(rawFrame, 0, rawFrame.length);
            }

            if (bitmap == null) return null;

            // Redimensionar a 720p para fluidez extrema (~30-60 FPS)
            int targetWidth = 720;
            int targetHeight = (int) (((float) targetWidth / bitmap.getWidth()) * bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
            bitmap.recycle();

            // Comprimir a JPEG ligero (~40 KB)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos);
            scaled.recycle();

            return baos.toByteArray();

        } catch (Exception e) {
            Log.e(TAG, "Error procesando frame RAW: " + e.getMessage());
            return null;
        }
    }
}
