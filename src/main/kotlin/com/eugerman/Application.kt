package com.eugerman

import com.eugerman.service.CsvParseService
import com.eugerman.service.CsvReportService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.BufferedReader
import java.io.InputStreamReader
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.partialcontent.*

fun main() {
    embeddedServer(Netty, port = 8085, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val csvParseService = CsvParseService()
    val csvReportService = CsvReportService()

    install(PartialContent)
    install(AutoHeadResponse)

    routing {
        post("/parsing") {
            val multiPartDataInputStream = call.receiveStream()
            val trades = csvParseService.parse(
                BufferedReader(InputStreamReader(multiPartDataInputStream))
            )
            val report = csvReportService.createReport(trades)

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "trades-summary-${System.currentTimeMillis()}.xlsx").toString()
            )
            call.respondFile(report)
            report.delete()
        }
    }
}