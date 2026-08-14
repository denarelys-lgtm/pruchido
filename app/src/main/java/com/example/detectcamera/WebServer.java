package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.util.*;

public class WebServer extends NanoHTTPD {

    private byte[] ultimoFramePantalla = null;
    private byte[] ultimoFrameCamara = null;
    private String usuarioValido = "";
    private String passwordValida = "";
    private CameraService cameraService;
    private final AudioStreamManager audioStreamManager = new AudioStreamManager();

    public WebServer(int port) { super(port); }

    public void setCameraService(CameraService service) { this.cameraService = service; }
    public void setCredenciales(String user, String pass) {
        this.usuarioValido = user != null ? user.trim() : "";
        this.passwordValida = pass != null ? pass.trim() : "";
    }

    public synchronized void actualizarFramePantalla(byte[] frame) { this.ultimoFramePantalla = frame; }
    public synchronized void actualizarFrameCamara(byte[] frame) { this.ultimoFrameCamara = frame; }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        // 1. STREAM MJPEG (El truco de fluidez)
        if ("/camera_stream".equals(uri)) {
            return new Response(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", new PipedInputStream() {
                // Generador de stream continuo
                @Override
                public int read() throws IOException {
                    try {
                        byte[] frame;
                        synchronized (WebServer.this) { frame = ultimoFrameCamara; }
                        if (frame == null) { Thread.sleep(100); return -1; }

                        String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        baos.write(header.getBytes());
                        baos.write(frame);
                        baos.write("\r\n".getBytes());
                        
                        byte[] data = baos.toByteArray();
                        Thread.sleep(40); // 25 FPS
                        return 0; // Solo para mantener el flujo activo
                    } catch (Exception e) { return -1; }
                }
            });
        }

        // 2. PANEL WEB
        String html = "<!DOCTYPE html><html><head><style>"
            + "body{background:#121212; color:#fff; font-family:sans-serif; text-align:center;}"
            + ".stream { width: 640px; height: 480px; background: #000; border: 2px solid #333; }"
            + "</style></head><body>"
            + "<h1>Live Monitor</h1>"
            + "<img class='stream' src='/camera_stream' />" // ¡Aquí está la magia! Solo ponemos el src
            + "</body></html>";
        return newFixedLengthResponse(html);
    }
}
