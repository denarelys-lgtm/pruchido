package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class WebServer extends NanoHTTPD {

    private byte[] ultimoFramePantalla = null;
    private byte[] ultimoFrameCamara = null;
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

    public synchronized void actualizarFramePantalla(byte[] frame) {
        this.ultimoFramePantalla = frame;
    }

    public synchronized void actualizarFrameCamara(byte[] frame) {
        this.ultimoFrameCamara = frame;
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

        // Endpoint de Audio
        if ("/audio.wav".equals(uri)) {
            InputStream audioStream = audioStreamManager.crearAudioStreamCliente();
            if (audioStream != null) {
                return newChunkedResponse(Response.Status.OK, "audio/wav", audioStream);
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error iniciando audio");
        }

        // Endpoint de Control de Cámara
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

        // Endpoint de Frames de Pantalla
        if ("/frame.png".equals(uri) || "/frame.jpg".equals(uri)) {
            byte[] frame;
            synchronized (this) {
                frame = ultimoFramePantalla;
            }
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(frame), frame.length);
            }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
        }

        // Endpoint de Frames de Cámara
        if ("/camera_frame.jpg".equals(uri)) {
            byte[] frame;
            synchronized (this) {
                frame = ultimoFrameCamara;
            }
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(frame), frame.length);
            }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
        }

        // Panel de Control Web Interactiva (HTML5 / CSS3 / JS)
        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Monitoreo</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 20px; }"
                + "h1 { color: #00E676; margin-bottom: 20px; }"
                + ".container { display: flex; flex-wrap: wrap; justify-content: center; gap: 20px; }"
                
                + "/* Tarjetas Redimensionables */"
                + ".card { background: #1e1e1e; padding: 15px; border-radius: 10px; border: 1px solid #333; "
                + "        resize: both; overflow: auto; min-width: 320px; min-height: 280px; width: 480px; height: 380px; "
                + "        display: flex; flex-direction: column; justify-content: space-between; box-shadow: 0 4px 10px rgba(0,0,0,0.5); }"
                
                + ".card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }"
                + ".card-header h3 { margin: 0; font-size: 16px; color: #00E676; }"
                
                + ".video-wrapper { flex: 1; display: flex; align-items: center; justify-content: center; background: #000; "
                + "                 overflow: hidden; border-radius: 6px; position: relative; width: 100%; height: 100%; }"
                
                + "/* Imagen de transmisión con efecto de rotación fluido */"
                + "img.stream { max-width: 100%; max-height: 100%; object-fit: contain; transition: transform 0.2s ease; }"
                
                + "button { padding: 8px 12px; margin: 2px; border: none; border-radius: 5px; font-weight: bold; cursor: pointer; color: white; font-size: 13px; }"
                + ".btn-on { background-color: #00E676; color: #000; }"
                + ".btn-off { background-color: #FF1744; }"
                + ".btn-toggle { background-color: #29B6F6; color: #000; }"
                + ".btn-tool { background-color: #424242; color: #fff; }"
                + ".btn-tool:hover { background-color: #616161; }"
                + ".btn-audio { background-color: #AA00FF; color: #fff; width: 100%; padding: 12px; font-size: 15px; margin-top: 10px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                
                + "<h1>Panel de Control de Monitoreo</h1>"
                + "<div class='container'>"

                // --- VENTANA 1: PANTALLA ---
                + "<div class='card' id='cardScreen'>"
                + "  <div class='card-header'>"
                + "    <h3>Transmisión de Pantalla</h3>"
                + "    <div>"
                + "      <button class='btn-tool' onclick=\"rotarImagen('screenImg')\">🔄 90°</button>"
                + "      <button class='btn-tool' onclick=\"pantallaCompleta('cardScreen')\">⛶ Max</button>"
                + "    </div>"
                + "  </div>"
                + "  <div class='video-wrapper'>"
                + "    <img src='/frame.jpg' id='screenImg' class='stream' alt='Esperando Transmisión...'>"
                + "  </div>"
                + "</div>"

                // --- VENTANA 2: CÁMARA ---
                + "<div class='card' id='cardCamera'>"
                + "  <div class='card-header'>"
                + "    <h3>Cámara en Vivo</h3>"
                + "    <div>"
                + "      <button class='btn-tool' onclick=\"rotarImagen('cameraImg')\">🔄 90°</button>"
                + "      <button class='btn-tool' onclick=\"pantallaCompleta('cardCamera')\">⛶ Max</button>"
                + "    </div>"
                + "  </div>"
                + "  <div class='video-wrapper'>"
                + "    <img src='/camera_frame.jpg' id='cameraImg' class='stream' alt='Cámara Apagada'>"
                + "  </div>"
                + "  <div style='margin-top: 10px;'>"
                + "    <button class='btn-on' onclick=\"fetch('/api/camera?action=on')\">Encender</button>"
                + "    <button class='btn-off' onclick=\"fetch('/api/camera?action=off')\">Apagar</button>"
                + "    <button class='btn-toggle' onclick=\"fetch('/api/camera?action=toggle')\">Cambiar Cámara</button>"
                + "  </div>"
                + "</div>"

                // --- VENTANA 3: AUDIO ---
                + "<div class='card' style='height: auto; min-height: 200px;'>"
                + "  <div class='card-header'>"
                + "    <h3>Audio del Micrófono</h3>"
                + "  </div>"
                + "  <p style='font-size: 14px; color: #ccc;'>Escucha el entorno del dispositivo en tiempo real.</p>"
                + "  <audio id='audioPlayer'></audio>"
                + "  <button id='audioBtn' class='btn-audio' onclick='toggleAudio()'>▶ Escuchar Micrófono</button>"
                + "</div>"

                + "</div>" // Fin .container

                // --- SCRIPTS INTERACTIVOS ---
                + "<script>"
                + "  var screenImg = document.getElementById('screenImg');"
                + "  var cameraImg = document.getElementById('cameraImg');"
                + "  var audioPlayer = document.getElementById('audioPlayer');"
                + "  var audioBtn = document.getElementById('audioBtn');"
                + "  var listening = false;"

                + "  // Grados de rotación almacenados para cada stream"
                + "  var rotaciones = { 'screenImg': 0, 'cameraImg': 90 };"

                + "  function rotarImagen(id) {"
                + "    rotaciones[id] = (rotaciones[id] + 90) % 360;"
                + "    document.getElementById(id).style.transform = 'rotate(' + rotaciones[id] + 'deg)';"
                + "  }"

                + "  function pantallaCompleta(cardId) {"
                + "    var elem = document.getElementById(cardId);"
                + "    if (!document.fullscreenElement) {"
                + "      if (elem.requestFullscreen) { elem.requestFullscreen(); }"
                + "      else if (elem.webkitRequestFullscreen) { elem.webkitRequestFullscreen(); }"
                + "    } else {"
                + "      if (document.exitFullscreen) { document.exitFullscreen(); }"
                + "    }"
                + "  }"

                + "  function toggleAudio() {"
                + "    if(!listening) {"
                + "      audioPlayer.src = '/audio.wav?' + new Date().getTime();"
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

                + "  function streamScreen() {"
                + "    var img = new Image();"
                + "    img.onload = function() {"
                + "      screenImg.src = this.src;"
                + "      setTimeout(streamScreen, 15);"
                + "    };"
                + "    img.onerror = function() {"
                + "      setTimeout(streamScreen, 100);"
                + "    };"
                + "    img.src = '/frame.jpg?' + new Date().getTime();"
                + "  }"

                + "  function streamCamera() {"
                + "    var img = new Image();"
                + "    img.onload = function() {"
                + "      cameraImg.src = this.src;"
                + "      setTimeout(streamCamera, 100);"
                + "    };"
                + "    img.onerror = function() {"
                + "      setTimeout(streamCamera, 200);"
                + "    };"
                + "    img.src = '/camera_frame.jpg?' + new Date().getTime();"
                + "  }"

                + "  streamScreen();"
                + "  streamCamera();"
                + "</script>"
                + "</body>"
                + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
}
