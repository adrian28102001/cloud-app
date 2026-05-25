package md.utm.cloudapp.repo

import md.utm.cloudapp.entity.Visit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class VisitRepositoryTest @Autowired constructor(
    private val repo: VisitRepository
) {
    @Test
    fun `saves and counts visits`() {
        assertThat(repo.count()).isZero()
        repo.save(Visit(at = Instant.now()))
        repo.save(Visit(at = Instant.now()))
        assertThat(repo.count()).isEqualTo(2)
    }
}
