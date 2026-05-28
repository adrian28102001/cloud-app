package md.utm.cloudapp.rest

import md.utm.cloudapp.entity.Visit
import md.utm.cloudapp.repo.VisitRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class MainController(private val visits: VisitRepository) {

    @GetMapping("/")
    fun main(): String {
        visits.save(Visit(at = Instant.now()))
        return "Hello World 2! Visit #${visits.count()}"
    }
}
