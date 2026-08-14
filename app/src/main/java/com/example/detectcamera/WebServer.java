package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class WebServer extends NanoHTTPD {

    // Almacenamiento atómico de los últimos frames
    private final AtomicReference<byte[]> ultimoFramePantalla = new AtomicReference<>();
    private final AtomicReference<byte[]> ultimoFrameCamara = new AtomicReference<>();

    // Listas de streams MJPEG activos para notificar nuevos frames
    private final CopyOnWriteArrayList<MJPEGInputStream> streamsPantalla = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MJPEGInputStream> streamsCamara = new CopyOnWriteArrayList<>();

    private String usuarioValido = "";
    private String passwordValida = "";
    private CameraService cameraService;
    private final AudioStreamManager audioStreamManager = new AudioStreamManager();

    public WebServer(int port) {
        super(port);
    }

    public void setCameraService(CameraService service) {
        this.cameraService = service;
    }

    public void setCredenciales(String user, String pass) {
        this.usuarioValido = user != null ? user.trim() : "";
        this.passwordValida = pass != null ? pass.trim() : "";
    }

    /**
     * Actualiza el frame de pantalla y notifica a todos los streams de pantalla.
     */
    public void actualizarFramePantalla(byte[] frame) {
        if (frame != null) {
            ultimoFramePantalla.set(frame);
            for (MJPEGInputStream stream : streamsPantalla) {
                stream.addFrame(frame);
            }
        } else {
            ultimoFramePantalla.set(null);
        }
    }

    /**
     * Actualiza el frame de cámara y notifica a todos los streams de cámara.
     */
    public void actualizarFrameCamara(byte[] frame) {
        if (frame != null) {
            ultimoFrameCamara.set(frame);
            for (MJPEGInputStream stream : streamsCamara) {
                stream.addFrame(frame);
            }
        } else {
            ultimoFrameCamara.set(null);
        }
    }

    public void detenerAudio() {
        audioStreamManager.detenerCaptura();
    }

    private boolean estaAutenticado(IHTTPSession session) {
        if (usuarioValido.isEmpty() || passwordValida.isEmpty()) {
            return true;
        }

        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64Creds = authHeader.substring(6).trim();
                String credenciales = new String(Base64.decode(base64Creds, Base64.DEFAULT));
                String[] partes = credenciales.split(":", 2);
                if (partes.length == 2) {
                    return usuarioValido.equals(partes[0]) && passwordValida.equals(partes[1]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (!estaAutenticado(session)) {
            Response response = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "text/plain",
                    "Acceso Denegado."
            );
            response.addHeader("WWW-Authenticate", "Basic realm=\"Acceso Restringido\"");
            return response;
        }

        String uri = session.getUri();

        // --- Streams MJPEG (alta fluidez) ---
        if ("/stream/screen".equals(uri)) {
            return createMJPEGResponse(ultimoFramePantalla, streamsPantalla);
        }
        if ("/stream/camera".equals(uri)) {
            return createMJPEGResponse(ultimoFrameCamara, streamsCamara);
        }

        // --- Endpoint de Audio ---
        if ("/audio.wav".equals(uri)) {
            InputStream audioStream = audioStreamManager.crearAudioStreamCliente();
            if (audioStream != null) {
                return newChunkedResponse(Response.Status.OK, "audio/wav", audioStream);
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error iniciando audio");
        }

        // --- Endpoint de Control de Cámara ---
        if ("/api/camera".equals(uri)) {
            String action = session.getParms().get("action");
            if (cameraService != null) {
                if ("on".equals(action)) {
                    cameraService.iniciarCamara();
                } else if ("off".equals(action)) {
                    cameraService.detenerCamara();
                } else if ("toggle".equals(action)) {
                    cameraService.alternarCamara();
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}");
        }

        // --- Endpoints de frame único (compatibilidad) ---
        if ("/frame.jpg".equals(uri)) {
            byte[] frame = ultimoFramePantalla.get();
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                        new ByteArrayInputStream(frame), frame.length);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No frame available");
        }

        if ("/camera_frame.jpg".equals(uri)) {
            byte[] frame = ultimoFrameCamara.get();
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                        new ByteArrayInputStream(frame), frame.length);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No frame available");
        }

        // --- Panel de Control Web (ahora con MJPEG) ---
        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Monitoreo</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 15px; }"
                + "h1 { color: #00E676; margin-bottom: 15px; font-size: 22px; }"
                + ".container { display: flex; flex-wrap: wrap; justify-content: center; gap: 15px; }"
                + ".card { background: #1e1e1e; padding: 12px; border-radius: 10px; border: 1px solid #333; "
                + "        resize: both; overflow: auto; min-width: 280px; min-height: 250px; width: 440px; height: 350px; "
                + "        display: flex; flex-direction: column; justify-content: space-between; box-shadow: 0 4px 10px rgba(0,0,0,0.5); }"
                + ".card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }"
                + ".card-header h3 { margin: 0; font-size: 15px; color: #00E676; }"
                + ".video-wrapper { flex: 1; display: flex; align-items: center; justify-content: center; background: #000; "
                + "                 overflow: hidden; border-radius: 6px; position: relative; width: 100%; height: 100%; }"
                + "img.stream { max-width: 100%; max-height: 100%; object-fit: contain; }"
                + "button { padding: 8px 12px; margin: 2px; border: none; border-radius: 5px; font-weight: bold; cursor: pointer; color: white; font-size: 13px; }"
                + ".btn-on { background-color: #00E676; color: #000; }"
                + ".btn-off { background-color: #FF1744; }"
                + ".btn-toggle { background-color: #29B6F6; color: #000; }"
                + ".btn-tool { background-color: #424242; color: #fff; }"
                + ".btn-tool:hover { background-color: #616161; }"
                + ".btn-audio { background-color: #AA00FF; color: #fff; width: 100%; padding: 10px; font-size: 14px; margin-top: 10px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h1>Panel de Control de Monitoreo</h1>"
                + "<div class='container'>"

                // VENTANA 1: PANTALLA
                + "<div class='card' id='cardScreen'>"
                + "  <div class='card-header'>"
                + "    <h3>Transmisión de Pantalla</h3>"
                + "    <div>"
                + "      <button class='btn-tool' onclick=\"rotarImagen('screenImg')\">🔄 90°</button>"
                + "      <button class='btn-tool' onclick=\"pantallaCompleta('cardScreen')\">⛶ Max</button>"
                + "    </div>"
                + "  </div>"
                + "  <div class='video-wrapper'>"
                + "    <img id='screenImg' class='stream' src='/stream/screen' alt='Cargando...'>"
                + "  </div>"
                + "</div>"

                // VENTANA 2: CÁMARA
                + "<div class='card' id='cardCamera'>"
                + "  <div class='card-header'>"
                + "    <h3>Cámara en Vivo</h3>"
                + "    <div>"
                + "      <button class='btn-tool' onclick=\"rotarImagen('cameraImg')\">🔄 90°</button>"
                + "      <button class='btn-tool' onclick=\"pantallaCompleta('cardCamera')\">⛶ Max</button>"
                + "    </div>"
                + "  </div>"
                + "  <div class='video-wrapper'>"
                + "    <img id='cameraImg' class='stream' src='/stream/camera' alt='Cámara Apagada'>"
                + "  </div>"
                + "  <div style='margin-top: 8px;'>"
                + "    <button class='btn-on' onclick=\"fetch('/api/camera?action=on')\">Encender</button>"
                + "    <button class='btn-off' onclick=\"fetch('/api/camera?action=off')\">Apagar</button>"
                + "    <button class='btn-toggle' onclick=\"fetch('/api/camera?action=toggle')\">Cambiar Cámara</button>"
                + "  </div>"
                + "</div>"

                // VENTANA 3: AUDIO
                + "<div class='card' style='height: auto; min-height: 180px;'>"
                + "  <div class='card-header'>"
                + "    <h3>Audio del Micrófono</h3>"
                + "  </div>"
                + "  <p style='font-size: 13px; color: #ccc; margin: 5px 0;'>Escucha el entorno en tiempo real.</p>"
                + "  <audio id='audioPlayer'></audio>"
                + "  <button id='audioBtn' class='btn-audio' onclick='toggleAudio()'>▶ Escuchar Micrófono</button>"
                + "</div>"

                + "</div>" // Fin container

                // JAVASCRIPT
                + "<script>"
                + "  var rotaciones = { 'screenImg': 0, 'cameraImg': 0 };"
                + "  var audioPlayer = document.getElementById('audioPlayer');"
                + "  var audioBtn = document.getElementById('audioBtn');"
                + "  var listening = false;"

                + "  function rotarImagen(id) {"
                + "    rotaciones[id] = (rotaciones[id] + 90) % 360;"
                + "    document.getElementById(id).style.transform = 'rotate(' + rotaciones[id] + 'deg)';"
                + "  }"

                + "  function pantallaCompleta(cardId) {"
                + "    var elem = document.getElementById(cardId);"
                + "    if (!document.fullscreenElement && !document.webkitFullscreenElement) {"
                + "      if (elem.requestFullscreen) { elem.requestFullscreen(); }"
                + "      else if (elem.webkitRequestFullscreen) { elem.webkitRequestFullscreen(); }"
                + "    } else {"
                + "      if (document.exitFullscreen) { document.exitFullscreen(); }"
                + "      else if (document.webkitExitFullscreen) { document.webkitExitFullscreen(); }"
                + "    }"
                + "  }"

                + "  function toggleAudio() {"
                + "    if(!listening) {"
                + "      audioPlayer.src = '/audio.wav?' + Date.now();"
                + "      audioPlayer.play();"
                + "      audioBtn.innerText = '⏹ Detener Audio';"
                + "      audioBtn.style.backgroundColor = '#FF1744';"
                + "      listening = true;"
                + "    } else {"
                + "      audioPlayer.pause();"
                + "      audioPlayer.src = '';"
                + "      audioBtn.innerText = '▶ Escuchar Micrófono';"
                + "      audioBtn.style.backgroundColor = '#AA00FF';"
                + "      listening = false;"
                + "    }"
                + "  }"

                // No se necesita polling: las imágenes MJPEG se actualizan solas
                + "</script>"
                + "</body>"
                + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    /**
     * Crea una respuesta MJPEG con un stream que bloquea hasta que haya nuevos frames.
     */
    private Response createMJPEGResponse(AtomicReference<byte[]> frameSource,
                                         CopyOnWriteArrayList<MJPEGInputStream> streamList) {
        MJPEGInputStream stream = new MJPEGInputStream();
        streamList.add(stream);

        // Enviar el frame actual si existe
        byte[] current = frameSource.get();
        if (current != null) {
            stream.addFrame(current);
        }

        // Registrar un hilo para eliminar el stream cuando se cierre la conexión
        stream.setOnCloseListener(() -> streamList.remove(stream));

        Response response = newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame",
                stream
        );
        return response;
    }

    /**
     * InputStream personalizado para MJPEG que bloquea cuando no hay datos.
     */
    private static class MJPEGInputStream extends InputStream {
        private static final byte[] BOUNDARY = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ".getBytes();
        private static final byte[] CRLF = "\r\n\r\n".getBytes();
        private static final byte[] END = "\r\n".getBytes();

        private final java.util.concurrent.BlockingQueue<byte[]> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private byte[] currentPacket = null;
        private int currentPos = 0;
        private volatile boolean closed = false;
        private Runnable closeListener;

        public void addFrame(byte[] jpeg) {
            if (!closed && jpeg != null && jpeg.length > 0) {
                // Construir el paquete multipart completo
                byte[] header = buildHeader(jpeg.length);
                byte[] packet = new byte[header.length + jpeg.length + END.length];
                System.arraycopy(header, 0, packet, 0, header.length);
                System.arraycopy(jpeg, 0, packet, header.length, jpeg.length);
                System.arraycopy(END, 0, packet, header.length + jpeg.length, END.length);
                queue.offer(packet);
            }
        }

        public void setOnCloseListener(Runnable listener) {
            this.closeListener = listener;
        }

        public void closeStream() {
            closed = true;
            queue.offer(new byte[0]); // Señal de fin
            if (closeListener != null) closeListener.run();
        }

        private byte[] buildHeader(int contentLength) {
            String headerStr = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + contentLength + "\r\n\r\n";
            return headerStr.getBytes();
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int n = read(oneByte, 0, 1);
            return n == -1 ? -1 : oneByte[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed && currentPacket == null) return -1;

            // Si no hay paquete actual, obtener uno nuevo (bloquea hasta 5s)
            if (currentPacket == null || currentPos >= currentPacket.length) {
                try {
                    byte[] packet = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (packet == null) {
                        // Timeout: enviar 0 bytes para mantener viva la conexión
                        return 0;
                    }
                    if (packet.length == 0) { // Señal de cierre
                        closed = true;
                        return -1;
                    }
                    currentPacket = packet;
                    currentPos = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }

            int bytesToCopy = Math.min(len, currentPacket.length - currentPos);
            System.arraycopy(currentPacket, currentPos, b, off, bytesToCopy);
            currentPos += bytesToCopy;
            if (currentPos >= currentPacket.length) {
                currentPacket = null; // Preparar para el siguiente
            }
            return bytesToCopy;
        }

        @Override
        public void close() throws IOException {
            closeStream();
            super.close();
        }
    }
}
