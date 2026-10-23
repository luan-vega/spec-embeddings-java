package br.codesearch.adapter.out.bedrock;

import br.codesearch.application.port.LanguageModel;
import br.codesearch.domain.CodeSearchException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * EXTERNO: modelos de linguagem do AWS Bedrock pela Converse API (Claude, Nova, Llama, Mistral...). A pergunta e os trechos
 * de código achados saem da máquina para a conta AWS configurada.
 */
public final class BedrockChat implements LanguageModel {

    private static final String UNAVAILABLE = "llm_unavailable";

    private final BedrockConnection connection;
    private final String model;

    public BedrockChat(BedrockConnection connection, String model) {
        if (model == null || model.isBlank()) {
            throw new CodeSearchException(UNAVAILABLE, "a model id is required for the bedrock LLM");
        }
        this.connection = connection;
        this.model = model;
    }

    public String host() {
        return connection.host();
    }

    @Override
    public String modelId() {
        return "bedrock:" + model;
    }

    @Override
    public boolean local() {
        return false;
    }

    @Override
    public String complete(String system, String user, int maxTokens) {
        String text = converse(connection, model, system, user, maxTokens, UNAVAILABLE).strip();
        if (text.isEmpty()) {
            throw new CodeSearchException(UNAVAILABLE, "the model returned an empty answer");
        }
        return text;
    }

    static String converse(BedrockConnection connection, String model, String system, String user, int maxTokens, String errorCode) {
        ConverseResponse resp;
        try {
            resp = connection.client().converse(r -> {
                r.modelId(model)
                        .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(user)).build())
                        .inferenceConfig(c -> c.maxTokens(maxTokens));
                if (system != null && !system.isBlank()) {
                    r.system(SystemContentBlock.fromText(system));
                }
            });
        } catch (RuntimeException e) {
            throw BedrockConnection.failure(e, errorCode, "Bedrock failed to answer");
        }
        StringBuilder text = new StringBuilder();
        if (resp.output() != null && resp.output().message() != null) {
            for (ContentBlock block : resp.output().message().content()) {
                if (block.text() != null) {
                    text.append(block.text());
                }
            }
        }
        return text.toString();
    }
}
