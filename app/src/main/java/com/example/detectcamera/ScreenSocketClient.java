package com.example.detectcamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ScreenSocketClient {

    private static final String TAG = "ScreenSocketClient";
    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9090;

    // Parámetros de salida
    private static final int TARGET_WIDTH = 640;      // Ancho objetivo para máxima fluidez
    private static final int TARGET_MAX_HEIGHT = 1000; // Alto máximo para pantallas verticales
    private static final int JPEG_QUALITY = 50;       // Calidad JPEG (más baja = más fluido)

    private final WebServer webServer;
    private volatile boolean running = false;
    private Thread workerThread;
    private Socket socket; // Referencia para cerrar en stop()

    // Reutilización del bitmap escalado y canvas para evitar GC
    private Bitmap scaledBitmap = null;
    private Canvas scaledCanvas = null;
    private final Paint scalePaint = new Paint(); // Sin filtro para máxima velocidad

    public ScreenSocketClient(WebServer webServer) {
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        workerThread = new Thread(() -> {
            while (running) {
                try {
                    socket = new Socket(HOST, PUERTO);
                    socket.setTcpNoDelay(true);            // Desactiva Nagle para latencia mínima
                    socket.setReceiveBufferSize(256 * 1024); // Buffer de recepción amplio
                    DataInputStream dis = new DataInputStream(socket.getInputStream());

                    Log.i(TAG, "Conectado al Daemon ADB en localhost:9090");

                    while (running && !socket.isClosed()) {
                        int length;
                        byte[] rawBytes; // <-- Declaración fuera del try para que sea visible
                        try {
                            length = dis.readInt();
                            if (length <= 0 || length > 30_000_000) {
                                continue; // Tamaño inválido, omitir
                            }
                            rawBytes = new byte[length];
                            dis.readFully(rawBytes);
                        } catch (IOException e) {
                            // Error de red -> salir para reconectar
                            throw e;
                        }

                        // Procesar frame fuera del bloque de red para separar errores
                        try {
                            byte[] jpeg = procesarRawFrameAJpeg(rawBytes);
                            if (jpeg != null && webServer != null) {
                                webServer.actualizarFramePantalla(jpeg);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error procesando frame, omitiendo", e);
                            // Continuar con el siguiente frame sin reconectar
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Conexión perdida, reintentando en 500ms...");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (IOException ignored) {}
                        socket = null;
                    }
                }
            }
        });

        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (webServer != null) {
            webServer.actualizarFramePantalla(null); // Limpiar frame
        }
    }

    /**
     * Convierte la captura RAW (screencap) en JPEG escalado y comprimido.
     */
    private byte[] procesarRawFrameAJpeg(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < 12) return null;

        Bitmap bitmap = null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawFrame);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            int width = buffer.getInt();
            int height = buffer.getInt();
            int format = buffer.getInt();

            // Determinar bytes por píxel según formato
            int bytesPerPixel;
            switch (format) {
                case 1: // RGBA_8888
                case 2: // RGBX_8888
                    bytesPerPixel = 4;
                    break;
                case 3: // RGB_888
                    bytesPerPixel = 3;
                    break;
                default:
                    Log.w(TAG, "Formato de píxel no soportado: " + format);
                    return null;
            }

            int pixelDataSize = width * height * bytesPerPixel;
            int headerSize = 12;
            // Ajustar header si hay bytes extra (p.ej. colorSpace)
            if (rawFrame.length - headerSize != pixelDataSize) {
                headerSize = 16;
                if (rawFrame.length - headerSize != pixelDataSize) {
                    headerSize = rawFrame.length - pixelDataSize; // Último recurso
                }
            }

            if (width <= 0 || height <= 0 || width > 4000 || height > 4000 ||
                    headerSize < 12 || headerSize + pixelDataSize > rawFrame.length) {
                // Posible frame ya codificado (JPEG/PNG) -> decodificar directamente
                bitmap = BitmapFactory.decodeByteArray(rawFrame, 0, rawFrame.length);
                if (bitmap == null) return null;
            } else {
                // Conversión según bytes por píxel
                if (bytesPerPixel == 4) {
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    ByteBuffer pixelBuffer = ByteBuffer.wrap(rawFrame, headerSize, pixelDataSize);
                    pixelBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    bitmap.copyPixelsFromBuffer(pixelBuffer);
                } else if (bytesPerPixel == 3) {
                    // Convertir RGB_888 a ARGB_8888 manualmente
                    int[] pixels = new int[width * height];
                    int offset = headerSize;
                    for (int i = 0; i < pixels.length; i++) {
                        int r = rawFrame[offset++] & 0xFF;
                        int g = rawFrame[offset++] & 0xFF;
                        int b = rawFrame[offset++] & 0xFF;
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                }
            }

            // Redimensionar y comprimir
            return escalarYComprimir(bitmap);

        } catch (Exception e) {
            Log.e(TAG, "Error procesando frame: " + e.getMessage());
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /**
     * Escala el bitmap a un tamaño objetivo y lo comprime a JPEG.
     */
    private byte[] escalarYComprimir(Bitmap original) {
        int origWidth = original.getWidth();
        int origHeight = original.getHeight();

        // Calcular escala para ajustar ancho y alto máximo
        float scale = Math.min(
                (float) TARGET_WIDTH / origWidth,
                (float) TARGET_MAX_HEIGHT / origHeight
        );
        if (scale > 1.0f) scale = 1.0f; // No ampliar

        int targetWidth = Math.round(origWidth * scale);
        int targetHeight = Math.round(origHeight * scale);

        // Reutilizar o crear bitmap escalado
        if (scaledBitmap == null || scaledBitmap.getWidth() != targetWidth
                || scaledBitmap.getHeight() != targetHeight) {
            if (scaledBitmap != null) {
                scaledBitmap.recycle();
                scaledCanvas = null;
            }
            scaledBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565);
            scaledCanvas = new Canvas(scaledBitmap);
        }

        // Dibujar original escalado sobre el bitmap reutilizado
        scaledCanvas.drawBitmap(original, null,
                new android.graphics.Rect(0, 0, targetWidth, targetHeight), scalePaint);

        // Comprimir a JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        return baos.toByteArray();
    }
}
