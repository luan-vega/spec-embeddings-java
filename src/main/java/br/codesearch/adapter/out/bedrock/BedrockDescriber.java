package br.codesearch.adapter.out.bedrock;

import br.codesearch.adapter.out.describer.DescriberPrompt;
import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.domain.CanonicalJson;
import br.codesearch.domain.CodeSearchException;
import java.util.LinkedHashMap;
import java.util.Map;

/** EXTERNO: descreve métodos com um modelo do AWS Bedrock (Converse API); o código do método sai da máquina. */
public final class BedrockDescriber implements ChunkDescriber {

    private static final String UNAVAILABLE = "describer_unavailable";

    private final BedrockConnection connection;
    private final String model;
    private final String id;

    public BedrockDescriber(BedrockConnection connection, String model) {
        if (model == null || model.isBlank()) {
            throw new CodeSearchException(UNAVAILABLE, "CODE_SEARCH_DESCRIBER_MODEL is required");
        }
        this.connection = connection;
        this.model = model;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("provider", "bedrock");
        fields.put("model", model);
        fields.put("prompt", DescriberPrompt.VERSION);
        this.id = CanonicalJson.shortDigest(fields);
    }

    public String host() {
        return connection.host();
    }

    @Override
    public String describerId() {
        return id;
    }

    @Override
    public boolean local() {
        return false;
    }

    @Override
    public String describe(String symbol, String code) {
        String reply = BedrockChat.converse(connection, model, "", DescriberPrompt.build(symbol, code),
                DescriberPrompt.MAX_OUTPUT_TOKENS, UNAVAILABLE);
        String text = DescriberPrompt.clean(reply);
        if (text.isEmpty()) {
            throw new CodeSearchException(UNAVAILABLE, "the describer model returned an empty description");
        }
        return text;
    }
}
