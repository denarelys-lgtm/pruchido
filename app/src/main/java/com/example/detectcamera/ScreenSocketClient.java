package com.example.detectcamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.Socket;

public class ScreenSocketClient {

    private static final String TAG = "ScreenSocketClient";

    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9090;

    private static final int MAX_FRAME_SIZE = 30_000_000;

    private final WebServer webServer;

    private volatile boolean running = false;
    private Thread workerThread;

    public ScreenSocketClient(WebServer webServer) {
        this.webServer = webServer;
    }

    public synchronized void start() {

        if (running) {
            return;
        }

        running = true;

        workerThread = new Thread(() -> {

            while (running) {

                try (Socket socket = new Socket(HOST, PUERTO);
                     DataInputStream dis =
                             new DataInputStream(socket.getInputStream())) {

                    Log.i(TAG,
                            "Conectado exitosamente al Daemon ADB en localhost:9090");

                    while (running && !socket.isClosed()) {

                        /*
                         * ScreenDaemon envía:
                         *
                         * [4 bytes - tamaño PNG]
                         * [PNG]
                         */

                        int length = dis.readInt();

                        if (length <= 0 || length > MAX_FRAME_SIZE) {

                            Log.w(TAG,
                                    "Tamaño de frame inválido: " + length);

                            break;
                        }

                        byte[] pngBytes = new byte[length];

                        dis.readFully(pngBytes);

                        /*
                         * El daemon YA está enviando PNG.
                         * No debemos interpretar estos bytes
                         * como un buffer RAW.
                         */

                        byte[] jpegFrame =
                                procesarFrame(pngBytes);

                        if (jpegFrame != null && webServer != null) {

                            webServer.actualizarFramePantalla(jpegFrame);
                        }
                    }

                } catch (Exception e) {

                    if (running) {

                        Log.w(TAG,
                                "Conexión con ScreenDaemon perdida. " +
                                "Reintentando en 2 segundos...",
                                e);
                    }

                    try {

                        Thread.sleep(2000);

                    } catch (InterruptedException ignored) {

                        Thread.currentThread().interrupt();
                    }
                }
            }

        }, "ScreenSocketClient");

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

        Log.i(TAG, "ScreenSocketClient detenido");
    }

    /**
     * Recibe directamente el PNG generado por:
     *
     * screencap -p
     *
     * Lo convierte a Bitmap, lo escala y finalmente
     * lo convierte a JPEG para enviarlo al WebServer.
     */
    private byte[] procesarFrame(byte[] pngBytes) {

        if (pngBytes == null || pngBytes.length == 0) {
            return null;
        }

        Bitmap bitmap = null;
        Bitmap scaled = null;

        try {

            /*
             * Decodificación directa del PNG.
             */
            bitmap = BitmapFactory.decodeByteArray(
                    pngBytes,
                    0,
                    pngBytes.length
            );

            if (bitmap == null) {

                Log.w(TAG,
                        "BitmapFactory no pudo decodificar el PNG");

                return null;
            }

            /*
             * Mantener la relación de aspecto.
             *
             * Limitamos el ancho a 720 px.
             * Si la imagen ya es menor, no la ampliamos.
             */
            int targetWidth = Math.min(
                    720,
                    bitmap.getWidth()
            );

            int targetHeight = Math.round(
                    ((float) targetWidth / bitmap.getWidth())
                            * bitmap.getHeight()
            );

            if (targetWidth != bitmap.getWidth()) {

                scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        targetWidth,
                        targetHeight,
                        true
                );

            } else {

                scaled = bitmap;
                bitmap = null;
            }

            /*
             * Convertir a JPEG para reducir el tamaño
             * de los frames que manejará el WebServer.
             */
            ByteArrayOutputStream baos =
                    new ByteArrayOutputStream();

            boolean compressed = scaled.compress(
                    Bitmap.CompressFormat.JPEG,
                    60,
                    baos
            );

            if (!compressed) {

                Log.w(TAG,
                        "No se pudo comprimir el frame a JPEG");

                return null;
            }

            return baos.toByteArray();

        } catch (Exception e) {

            Log.e(TAG,
                    "Error procesando frame de pantalla",
                    e);

            return null;

        } finally {

            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            if (scaled != null
                    && scaled != bitmap
                    && !scaled.isRecycled()) {

                scaled.recycle();
            }
        }
    }
}
