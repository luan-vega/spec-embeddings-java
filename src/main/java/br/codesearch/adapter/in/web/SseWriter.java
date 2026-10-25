package br.codesearch.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server-Sent Events sobre a resposta do servlet. Os cabeçalhos só são enviados no primeiro evento: até lá, um erro ainda
 * pode virar uma resposta JSON comum com o status certo. Cada evento é um objeto JSON numa linha {@code data:}, seguido de
 * flush. Se o cliente desconectar, {@link ClientGone} interrompe a pergunta (e a geração do modelo) em vez de seguir à toa.
 */
final class SseWriter {

    /** O cliente fechou a conexão no meio do streaming. */
    static final class ClientGone extends RuntimeException {
        ClientGone() {
            super("client disconnected", null, false, false);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServletResponse response;
    private OutputStream out;

    SseWriter(HttpServletResponse response) {
        this.response = response;
    }

    boolean started() {
        return out != null;
    }

    synchronized void send(String event, Object data) {
        try {
            if (out == null) {
                response.setStatus(200);
                response.setContentType("text/event-stream;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Accel-Buffering", "no");
                out = response.getOutputStream();
            }
            String frame = "event: " + event + "\ndata: " + JSON.writeValueAsString(data) + "\n\n";
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ClientGone();
        }
    }
}
