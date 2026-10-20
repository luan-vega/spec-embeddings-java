package br.codesearch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Paridade com a versão Python: os valores esperados vêm de EmbeddingProfile.profile_id de lá. */
class EmbeddingProfileTest {

    @Test
    void profileIdMatchesPythonForNomicProfile() {
        var profile = new EmbeddingProfile(
                "nomic-embed-text",
                "sha256:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f",
                768, "", "", 2048);
        assertThat(profile.profileId()).isEqualTo("3440ee7b5fc9bde0");
    }

    @Test
    void profileIdMatchesPythonWithPrefixes() {
        var profile = new EmbeddingProfile("m", "sha256:x", 4, "search_document: ", "search_query: ", 8);
        assertThat(profile.profileId()).isEqualTo("937fdb6079bffe1b");
    }

    @Test
    void rejectsNonPositiveDimension() {
        assertThatThrownBy(() -> new EmbeddingProfile("m", "d", 0, "", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
