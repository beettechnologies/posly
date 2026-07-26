package com.beettechnologies.posly.backup

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureBackupRoutes(backupService: BackupService, restoreService: RestoreService) {
    routing {
        authenticate("jwt-auth") {
            route("/ops/backups") {
                withRole(Role.ADMIN) {
                    post("/run-now") {
                        val metadata = backupService.runBackupNow()
                        val status = if (metadata.status == BackupStatus.SUCCESS) HttpStatusCode.Created else HttpStatusCode.InternalServerError
                        call.respond(status, metadata.toResponse())
                    }

                    get {
                        call.respond(HttpStatusCode.OK, backupService.listBackups().map { it.toResponse() })
                    }

                    post("/{id}/restore") {
                        val id = call.parameters["id"]!!
                        val req = runCatching { call.receive<RestoreRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        when (val result = restoreService.restore(id, req.targetJdbcUrl)) {
                            is RestoreResult.Success -> call.respond(HttpStatusCode.OK, result.drill.toResponse())
                            RestoreResult.BackupNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Backup not found"))
                            RestoreResult.SourceBackupNotUsable -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Backup did not complete successfully and cannot be restored"))
                            RestoreResult.RefusedProductionTarget -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Refusing to restore into the application's own live database - target a separate sandbox/DR database")
                            )
                        }
                    }
                }
            }
        }
    }
}
