package org.stockwellness.adapter.out.external.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.retry.annotation.Retryable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiAdapterWeatherFailureTest {

    @Mock
    private ChatClient.Builder builder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptTemplateMapper promptTemplateMapper;

    private OpenAiAdapter adapter;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(chatClient);
        adapter = new OpenAiAdapter(builder, promptTemplateMapper);
    }

    @Test
    void marketWeatherFailurePropagatesWithoutAdapterRetry() throws Exception {
        when(promptTemplateMapper.getMarketWeatherSystemInstruction()).thenReturn("system");
        when(promptTemplateMapper.toMarketWeatherPrompt("KOSPI", 70, "news")).thenReturn("user");
        when(chatClient.prompt()).thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> adapter.generateMarketWeatherSummary(70, "KOSPI", "news"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Market Weather Summary failed");
        assertThat(OpenAiAdapter.class
                .getMethod("generateMarketWeatherSummary", int.class, String.class, String.class)
                .isAnnotationPresent(Retryable.class))
                .isFalse();
    }

    @Test
    void sectorWeatherFailurePropagatesWithoutAdapterRetry() throws Exception {
        when(promptTemplateMapper.getSectorWeatherSystemInstruction()).thenReturn("system");
        when(promptTemplateMapper.toSectorWeatherPrompt("전기전자", 80, "news")).thenReturn("user");
        when(chatClient.prompt()).thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> adapter.generateSectorWeatherInsight("전기전자", 80, "news"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sector Weather Insight failed");
        assertThat(OpenAiAdapter.class
                .getMethod("generateSectorWeatherInsight", String.class, int.class, String.class)
                .isAnnotationPresent(Retryable.class))
                .isFalse();
    }
}
