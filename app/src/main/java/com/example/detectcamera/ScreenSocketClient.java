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

                    Log.i(
                            TAG,
                            "Conectado exitosamente al Daemon ADB en localhost:9090"
                    );

                    while (running && !socket.isClosed()) {

                        /*
                         * ScreenDaemon envía:
                         *
                         * [4 bytes - tamaño del frame]
                         * [datos del frame]
                         */

                        int length = dis.readInt();

                        if (length <= 0 || length > MAX_FRAME_SIZE) {

                            Log.w(
                                    TAG,
                                    "Tamaño de frame inválido: " + length
                            );

                            break;
                        }

                        byte[] frameBytes = new byte[length];

                        dis.readFully(frameBytes);

                        /*
                         * Intentamos procesar automáticamente
                         * el formato recibido.
                         */
                        byte[] jpegFrame =
                                procesarFrame(frameBytes);

                        if (jpegFrame != null && webServer != null) {

                            webServer.actualizarFramePantalla(jpegFrame);
                        }
                    }

                } catch (Exception e) {

                    if (running) {

                        Log.w(
                                TAG,
                                "Conexión con ScreenDaemon perdida. " +
                                "Reintentando en 2 segundos..."
                        );
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
     * Procesa automáticamente el frame recibido.
     *
     * Soporta:
     *
     * 1. PNG
     * 2. JPEG
     * 3. RAW de screencap
     */
    private byte[] procesarFrame(byte[] frame) {

        if (frame == null || frame.length == 0) {
            return null;
        }

        /*
         * ---------------------------------------------------------
         * PRIMERA OPCIÓN:
         * Intentar decodificar directamente como imagen.
         *
         * Esto permite trabajar con PNG/JPEG.
         * ---------------------------------------------------------
         */

        Bitmap bitmap = BitmapFactory.decodeByteArray(
                frame,
                0,
                frame.length
        );

        if (bitmap != null) {

            Log.d(
                    TAG,
                    "Frame detectado como imagen comprimida: "
                            + bitmap.getWidth()
                            + "x"
                            + bitmap.getHeight()
            );

            return convertirBitmapAJpeg(bitmap);
        }

        /*
         * ---------------------------------------------------------
         * SEGUNDA OPCIÓN:
         * Intentar interpretar el frame como RAW.
         *
         * Conservamos aquí la lógica de tu versión que
         * anteriormente mostraba la pantalla.
         * ---------------------------------------------------------
         */

        return procesarRawFrameAJpeg(frame);
    }

    /**
     * Procesa el formato RAW utilizado por screencap.
     */
    private byte[] procesarRawFrameAJpeg(byte[] rawFrame) {

        if (rawFrame == null || rawFrame.length < 12) {
            return null;
        }

        Bitmap bitmap = null;

        try {

            ByteBuffer buffer =
                    ByteBuffer.wrap(rawFrame);

            buffer.order(
                    ByteOrder.LITTLE_ENDIAN
            );

            int width = buffer.getInt();
            int height = buffer.getInt();
            int format = buffer.getInt();

            Log.d(
                    TAG,
                    "RAW detectado: "
                            + width
                            + "x"
                            + height
                            + " format="
                            + format
            );

            /*
             * Validaciones para evitar que bytes de un PNG,
             * JPEG o un frame corrupto sean interpretados
             * como dimensiones absurdas.
             */

            if (width <= 0
                    || height <= 0
                    || width > 4000
                    || height > 4000) {

                Log.w(
                        TAG,
                        "Dimensiones RAW inválidas: "
                                + width
                                + "x"
                                + height
                );

                return null;
            }

            /*
             * Cada píxel ocupa 4 bytes en ARGB_8888.
             */
            long pixelDataSizeLong =
                    (long) width
                            * (long) height
                            * 4L;

            if (pixelDataSizeLong <= 0
                    || pixelDataSizeLong > rawFrame.length) {

                Log.w(
                        TAG,
                        "Tamaño de datos RAW inválido: "
                                + pixelDataSizeLong
                );

                return null;
            }

            int pixelDataSize =
                    (int) pixelDataSizeLong;

            /*
             * Conservamos la misma estrategia del código
             * original que ya funcionaba en tu dispositivo.
             */
            int headerOffset =
                    rawFrame.length - pixelDataSize;

            if (headerOffset < 12
                    || headerOffset >= rawFrame.length) {

                Log.w(
                        TAG,
                        "Offset RAW inválido: "
                                + headerOffset
                );

                return null;
            }

            bitmap = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
            );

            buffer.position(headerOffset);

            /*
             * Copiar los píxeles RAW directamente al Bitmap.
             */
            buffer.limit(
                    Math.min(
                            rawFrame.length,
                            headerOffset + pixelDataSize
                    )
            );

            bitmap.copyPixelsFromBuffer(buffer);

            Log.d(
                    TAG,
                    "Frame RAW procesado correctamente"
            );

            return convertirBitmapAJpeg(bitmap);

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error procesando frame RAW",
                    e
            );

            return null;

        } finally {

            if (bitmap != null
                    && !bitmap.isRecycled()) {

                bitmap.recycle();
            }
        }
    }

    /**
     * Convierte un Bitmap a JPEG optimizado para el panel web.
     *
     * Mantiene la relación de aspecto y limita el ancho
     * a 720 píxeles.
     */
    private byte[] convertirBitmapAJpeg(Bitmap bitmap) {

        if (bitmap == null) {
            return null;
        }

        Bitmap scaled = null;

        try {

            int originalWidth =
                    bitmap.getWidth();

            int originalHeight =
                    bitmap.getHeight();

            if (originalWidth <= 0
                    || originalHeight <= 0) {

                return null;
            }

            /*
             * Limitar a 720 px de ancho.
             *
             * No ampliamos imágenes que ya sean menores.
             */
            int targetWidth =
                    Math.min(
                            720,
                            originalWidth
                    );

            int targetHeight =
                    Math.round(
                            ((float) targetWidth
                                    / originalWidth)
                                    * originalHeight
                    );

            if (targetWidth != originalWidth) {

                scaled =
                        Bitmap.createScaledBitmap(
                                bitmap,
                                targetWidth,
                                targetHeight,
                                true
                        );

            } else {

                scaled = bitmap;
            }

            /*
             * JPEG calidad 60.
             *
             * Es suficientemente ligero para transmisión
             * continua y mantiene una calidad razonable.
             */
            ByteArrayOutputStream baos =
                    new ByteArrayOutputStream();

            boolean success =
                    scaled.compress(
                            Bitmap.CompressFormat.JPEG,
                            60,
                            baos
                    );

            if (!success) {

                Log.w(
                        TAG,
                        "No se pudo comprimir el Bitmap a JPEG"
                );

                return null;
            }

            byte[] jpeg =
                    baos.toByteArray();

            Log.d(
                    TAG,
                    "JPEG generado: "
                            + targetWidth
                            + "x"
                            + targetHeight
                            + " / "
                            + jpeg.length
                            + " bytes"
            );

            return jpeg;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error convirtiendo Bitmap a JPEG",
                    e
            );

            return null;

        } finally {

            /*
             * Si scaled es un Bitmap diferente al original,
             * liberarlo aquí.
             *
             * Si es el mismo, no lo reciclamos porque puede
             * pertenecer al Bitmap recibido por el método.
             */
            if (scaled != null
                    && scaled != bitmap
                    && !scaled.isRecycled()) {

                scaled.recycle();
            }
        }
    }
}
