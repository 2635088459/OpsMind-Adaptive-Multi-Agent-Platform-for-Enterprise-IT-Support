package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-004 §7: content validation and secret rejection. */
@Tag("unit")
class MessageContentTest {

    @Test
    void shouldAcceptTrimmedContentWithinLength() {
        MessageContent content = MessageContent.of("  Hello support team  ");

        assertThat(content.value()).isEqualTo("Hello support team");
    }

    @Test
    void shouldRejectBlankContent() {
        assertThatThrownBy(() -> MessageContent.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullContent() {
        assertThatThrownBy(() -> MessageContent.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectContentOverMaxLength() {
        String tooLong = "a".repeat(8001);

        assertThatThrownBy(() -> MessageContent.of(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptContentAtExactlyMaxLength() {
        String exact = "a".repeat(8000);

        assertThat(MessageContent.of(exact).value()).hasSize(8000);
    }

    @Test
    void shouldRejectDangerousControlCharacters() {
        String withBellCharacter = "hello" + '' + "world";

        assertThatThrownBy(() -> MessageContent.of(withBellCharacter)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowNewlinesAndTabs() {
        MessageContent content = MessageContent.of("line one\nline two\ttabbed");

        assertThat(content.value()).contains("\n").contains("\t");
    }

    @Test
    void shouldRejectPrivateKeyBlock() {
        String withKey = "Here is my key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow==\n-----END RSA PRIVATE KEY-----";

        assertThatThrownBy(() -> MessageContent.of(withKey))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("BEGIN RSA PRIVATE KEY");
    }

    @Test
    void shouldRejectPasswordAssignment() {
        assertThatThrownBy(() -> MessageContent.of("my password=hunter2 for the vpn"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBearerToken() {
        assertThatThrownBy(() -> MessageContent.of("use Bearer abcdefghijklmnop123456 to authenticate"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAwsAccessKey() {
        assertThatThrownBy(() -> MessageContent.of("key is AKIAABCDEFGHIJKLMNOP"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotEchoTheSecretInTheRejectionMessage() {
        String secret = "sUp3rSecr3tSauce";
        assertThatThrownBy(() -> MessageContent.of("password=" + secret))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining(secret);
    }

    @Test
    void shouldAllowOrdinaryTextMentioningTheWordPassword() {
        MessageContent content = MessageContent.of("I forgot my password and need help resetting it.");

        assertThat(content.value()).contains("password");
    }
}
