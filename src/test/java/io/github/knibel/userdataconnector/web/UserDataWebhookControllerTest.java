package io.github.knibel.userdataconnector.web;

import io.github.knibel.userdataconnector.UserDataConnectorAutoConfiguration;
import io.github.knibel.userdataconnector.api.UserDataRepository;
import io.github.knibel.userdataconnector.store.InMemoryUserDataStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {UserDataConnectorAutoConfiguration.class, WebTestApplication.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "user-data-connector.source-type=WEBHOOK",
        "user-data-connector.webhook.path=/webhook/user-data",
        "user-data-connector.webhook.secret=test-secret"
})
class UserDataWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDataRepository userDataRepository;

    @Autowired
    private InMemoryUserDataStore store;

    @Test
    void upsertPayload_storesData() throws Exception {
        mockMvc.perform(post("/webhook/user-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Secret", "test-secret")
                        .content("""
                                {
                                  "userId": "alice",
                                  "eventType": "UPSERT",
                                  "attributes": { "email": "alice@example.com" }
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(userDataRepository.findByUserId("alice")).isPresent()
                .hasValueSatisfying(d -> assertThat(d.getAttribute("email")).isEqualTo("alice@example.com"));
    }

    @Test
    void deletePayload_removesData() throws Exception {
        // First insert the user
        store.upsert(new io.github.knibel.userdataconnector.api.UserIdentityData(
                "bob", java.util.Map.of("name", "Bob")));
        assertThat(userDataRepository.exists("bob")).isTrue();

        mockMvc.perform(post("/webhook/user-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Secret", "test-secret")
                        .content("""
                                { "userId": "bob", "eventType": "DELETE" }
                                """))
                .andExpect(status().isOk());

        assertThat(userDataRepository.exists("bob")).isFalse();
    }

    @Test
    void invalidSecret_returns401() throws Exception {
        mockMvc.perform(post("/webhook/user-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Secret", "wrong-secret")
                        .content("""
                                { "userId": "carol", "eventType": "UPSERT", "attributes": {} }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingSecret_returns401() throws Exception {
        mockMvc.perform(post("/webhook/user-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "userId": "dave", "eventType": "UPSERT", "attributes": {} }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingUserId_returns400() throws Exception {
        mockMvc.perform(post("/webhook/user-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Secret", "test-secret")
                        .content("""
                                { "eventType": "UPSERT", "attributes": { "k": "v" } }
                                """))
                .andExpect(status().isBadRequest());
    }
}
