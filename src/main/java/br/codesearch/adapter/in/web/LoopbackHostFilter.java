package br.codesearch.adapter.in.web;

import br.codesearch.adapter.in.Payloads;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.Loopback;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Barreira contra DNS rebinding: o cabeçalho Host precisa ser loopback. */
@Component
class LoopbackHostFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static String hostName(String header) {
        String host = header == null ? "" : header.strip();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host;
        }
        int colons = host.length() - host.replace(":", "").length();
        return colons == 1 ? host.substring(0, host.lastIndexOf(':')) : host;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!Loopback.isLoopback(hostName(request.getHeader("Host")))) {
            byte[] body = JSON.writeValueAsBytes(Payloads.error(new CodeSearchException("invalid_request", "invalid Host header"), null));
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        chain.doFilter(request, response);
    }
}
