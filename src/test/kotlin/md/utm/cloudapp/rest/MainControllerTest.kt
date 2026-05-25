package md.utm.cloudapp.rest

import md.utm.cloudapp.entity.Visit
import md.utm.cloudapp.repo.VisitRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(MainController::class)
@ActiveProfiles("test")
class MainControllerTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @MockBean
    lateinit var repo: VisitRepository

    @Test
    fun `returns hello world with current visit count`() {
        whenever(repo.save(any<Visit>())).thenAnswer { it.arguments[0] }
        whenever(repo.count()).thenReturn(42L)

        mvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hello World")))
            .andExpect(content().string(containsString("42")))
    }
}
