package org.stockwellness.adapter.out.external.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TavilyAdapterTest {

    @Mock
    private RestClient.Builder restClientBuilder;

    @Test
    void searchFailureReturnsEmptyNewsContext() {
        TavilyAdapter adapter = new TavilyAdapter(restClientBuilder);
        ReflectionTestUtils.setField(adapter, "apiKey", "test-key");
        when(restClientBuilder.build()).thenThrow(new IllegalStateException("provider unavailable"));

        assertThat(adapter.searchFinancialNews("KOSPI 시장 현황")).isEmpty();
    }
}
