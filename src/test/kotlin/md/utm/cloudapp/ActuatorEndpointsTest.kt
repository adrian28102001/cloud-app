package md.utm.cloudapp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorEndpointsTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @Test
    fun `liveness probe returns 200`() {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
    }

    @Test
    fun `readiness probe returns 200`() {
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
    }

    @Test
    fun `prometheus endpoint exposes metrics in exposition format`() {
        val body = mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        assertThat(body).contains("# TYPE")
    }
}
