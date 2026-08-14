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

        // Endpoint de Audio en vivo
        if ("/audio.wav".equals(uri)) {
            InputStream audioStream = audioStreamManager.crearAudioStreamCliente();
            if (audioStream != null) {
                return newChunkedResponse(Response.Status.OK, "audio/wav", audioStream);
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error iniciando audio");
        }

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

        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Monitoreo</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 20px; }"
                + "h1 { color: #00E676; margin-bottom: 20px; }"
                + ".container { display: flex; flex-wrap: wrap; justify-content: center; gap: 20px; }"
                + ".card { background: #1e1e1e; padding: 15px; border-radius: 10px; border: 1px solid #333; max-width: 450px; width: 100%; }"
                + "img { width: 100%; height: auto; border-radius: 6px; background: #000; min-height: 250px; object-fit: contain; }"
                + "button { padding: 10px 15px; margin: 5px; border: none; border-radius: 5px; font-weight: bold; cursor: pointer; color: white; }"
                + ".btn-on { background-color: #00E676; color: #000; }"
                + ".btn-off { background-color: #FF1744; }"
                + ".btn-toggle { background-color: #29B6F6; color: #000; }"
                + ".btn-audio { background-color: #AA00FF; color: #fff; width: 100%; padding: 12px; font-size: 16px; margin-top: 10px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h1>Panel de Control de Monitoreo</h1>"
                + "<div class='container'>"
                
                + "<div class='card'>"
                + "<h3>Transmisión de Pantalla (High FPS)</h3>"
                + "<img src='/frame.jpg' id='screenImg' alt='Esperando Daemon ADB...'>"
                + "</div>"

                + "<div class='card'>"
                + "<h3>Cámara en Vivo</h3>"
                + "<img src='/camera_frame.jpg' id='cameraImg' alt='Cámara Apagada'>"
                + "<div style='margin-top: 15px;'>"
                + "<button class='btn-on' onclick=\"fetch('/api/camera?action=on')\">Encender Cámara</button>"
                + "<button class='btn-off' onclick=\"fetch('/api/camera?action=off')\">Apagar Cámara</button>"
                + "<button class='btn-toggle' onclick=\"fetch('/api/camera?action=toggle')\">Cambiar Cámara</button>"
                + "</div>"
                + "</div>"

                + "<div class='card'>"
                + "<h3>Audio del Micrófono</h3>"
                + "<p>Escucha el entorno del dispositivo en tiempo real.</p>"
                + "<audio id='audioPlayer'></audio>"
                + "<button id='audioBtn' class='btn-audio' onclick='toggleAudio()'>▶ Escuchar Micrófono</button>"
                + "</div>"

                + "</div>"

                + "<script>"
                + "  var screenImg = document.getElementById('screenImg');"
                + "  var cameraImg = document.getElementById('cameraImg');"
                + "  var audioPlayer = document.getElementById('audioPlayer');"
                + "  var audioBtn = document.getElementById('audioBtn');"
                + "  var listening = false;"

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
