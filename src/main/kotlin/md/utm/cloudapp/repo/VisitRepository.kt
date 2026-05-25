package md.utm.cloudapp.repo

import md.utm.cloudapp.entity.Visit
import org.springframework.data.jpa.repository.JpaRepository

interface VisitRepository : JpaRepository<Visit, Long>
