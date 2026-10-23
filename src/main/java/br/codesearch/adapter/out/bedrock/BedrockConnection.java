package br.codesearch.adapter.out.bedrock;

import br.codesearch.domain.CodeSearchException;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;

/**
 * Conexão EXTERNA com o AWS Bedrock Runtime. As credenciais vêm da cadeia padrão da AWS (variáveis AWS_ACCESS_KEY_ID /
 * AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN, AWS_PROFILE, SSO ou role do ambiente); nada é lido nem guardado aqui. Mensagens de
 * erro nunca repassam o texto da AWS (pode ecoar o código enviado).
 */
public final class BedrockConnection {

    private final BedrockRuntimeClient client;
    private final String host;

    private BedrockConnection(BedrockRuntimeClient client, String host) {
        this.client = client;
        this.host = host;
    }

    /** {@code region} vazia usa a região da cadeia padrão (AWS_REGION, perfil); {@code endpoint} vazio usa o endereço oficial. */
    public static BedrockConnection create(String region, String endpoint, String errorCode) {
        try {
            var builder = BedrockRuntimeClient.builder()
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(120)));
            if (region != null && !region.isBlank()) {
                builder.region(Region.of(region.strip()));
            }
            String host;
            if (endpoint != null && !endpoint.isBlank()) {
                URI uri = URI.create(endpoint.strip());
                builder.endpointOverride(uri);
                host = uri.getHost();
            } else {
                BedrockRuntimeClient probe = builder.build();
                host = "bedrock-runtime." + probe.serviceClientConfiguration().region().id() + ".amazonaws.com";
                return new BedrockConnection(probe, host);
            }
            return new BedrockConnection(builder.build(), host);
        } catch (SdkClientException | IllegalArgumentException e) {
            throw new CodeSearchException(errorCode, "AWS region is not set (CODE_SEARCH_BEDROCK_REGION or AWS_REGION)");
        }
    }

    /** Para testes e para quem já tem um cliente configurado. */
    public static BedrockConnection of(BedrockRuntimeClient client, String host) {
        return new BedrockConnection(client, host);
    }

    BedrockRuntimeClient client() {
        return client;
    }

    public String host() {
        return host;
    }

    /** Erro estável por tipo, sem repassar a mensagem da AWS. */
    static CodeSearchException failure(RuntimeException e, String errorCode, String what) {
        String reason;
        if (e instanceof BedrockRuntimeException b) {
            reason = switch (b.statusCode()) {
                case 401, 403 -> "access denied (check IAM permissions and model access in the Bedrock console)";
                case 429 -> "rate limited, retry later";
                case 400, 413, 422 -> "input rejected by Bedrock (or invalid model id)";
                default -> "HTTP " + b.statusCode();
            };
            boolean rejected = b.statusCode() == 400 || b.statusCode() == 413 || b.statusCode() == 422;
            if (rejected && errorCode.equals("embedder_unavailable")) {
                return new CodeSearchException("embedder_invalid_output", what + ": " + reason);
            }
        } else if (e instanceof SdkClientException) {
            reason = "not reachable or no AWS credentials (check the credential chain and region)";
        } else {
            reason = "unexpected error";
        }
        return new CodeSearchException(errorCode, what + ": " + reason);
    }
}
