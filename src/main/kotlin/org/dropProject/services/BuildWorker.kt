/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 Pedro Alves
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.dropProject.services

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.dropProject.dao.*
import org.dropProject.data.BuildReport
import org.dropProject.data.TestType
import org.dropProject.repository.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.*
import java.util.logging.Logger

/**
 * Determines if a project's POM file contains the configuration to calculate the code's test coverage report.
 * @param mavenizedProjectFolder
 * @return a Boolean
 */
fun hasCoverageReport(mavenizedProjectFolder: File): Boolean {
    val pomFile = File("${mavenizedProjectFolder}/pom.xml")
    return pomFile.readText().contains("jacoco-maven-plugin")
}

/**
 * This class contains functions that execute the build process for [Assignment]s and [Submission]s.
 */
@Service
class BuildWorker(
        val mavenInvoker: MavenInvoker,
        val gradleInvoker: GradleInvoker,
        val assignmentRepository: AssignmentRepository,
        val submissionRepository: SubmissionRepository,
        val gitSubmissionRepository: GitSubmissionRepository,
        val submissionReportRepository: SubmissionReportRepository,
        val buildReportRepository: BuildReportRepository,
        val jUnitReportRepository: JUnitReportRepository,
        val jacocoReportRepository: JacocoReportRepository,
        val buildReportBuilder: BuildReportBuilder) {

    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    /**
     * NEW: Added Gradle compiler to the build worker
     * Checks a [Submission], performing all relevant build and evalutation steps (for example, Compilation) and storing
     * each step's results in the database.
     *
     * @param projectFolder is a File
     * @param authorsStr is a String
     * @param submission is a [Submission]
     * @param principalName is a String
     * @param dontChangeStatusDate is a Boolean
     * @param rebuildByTeacher is a Boolean
     */
    @Async
    @Transactional
    fun checkProject(projectFolder: File, authorsStr: String, submission: Submission,
                          principalName: String?, dontChangeStatusDate: Boolean = false, rebuildByTeacher: Boolean = false) {

        val assignment = assignmentRepository.findById(submission.assignmentId).orElse(null)

        //NEW: Added condition to check if either Maven or Gradle are used
        val result: Result(0)
        if (assignment.compiler == Compiler.MAVEN) {
            if (assignment.maxMemoryMb != null) {
                LOG.info("[${authorsStr}] Started maven invocation (max: ${assignment.maxMemoryMb}Mb)")
            } else {
                LOG.info("[${authorsStr}] Started maven invocation")
            }

            val realPrincipalName = if (rebuildByTeacher) submission.submitterUserId else principalName
            result = mavenInvoker.run(projectFolder, realPrincipalName, assignment.maxMemoryMb)
    
            LOG.info("[${authorsStr}] Finished maven invocation")
        } else { // gradle is used as compiler
            if (assignment.maxMemoryMb != null) {
                LOG.info("[${authorsStr}] Started gradle invocation (max: ${assignment.maxMemoryMb}Mb)")
            } else {
                LOG.info("[${authorsStr}] Started gradle invocation")
            }

            val realPrincipalName = if (rebuildByTeacher) submission.submitterUserId else principalName
            result = gradleInvoker.run(projectFolder, realPrincipalName, assignment.maxMemoryMb)
        }

        // check result for errors (expired, too much output)
        when {
            result.expiredByTimeout -> submission.setStatus(SubmissionStatus.ABORTED_BY_TIMEOUT,
                                                                    dontUpdateStatusDate = dontChangeStatusDate)
            result.tooMuchOutput() -> submission.setStatus(SubmissionStatus.TOO_MUCH_OUTPUT,
                                                                    dontUpdateStatusDate = dontChangeStatusDate)
            else -> { // get build report
                LOG.info("[${authorsStr}] Invoker OK")
                val buildReport = buildReportBuilder.build(result.outputLines, projectFolder.absolutePath,
                        assignment, submission)

                // clear previous indicators except PROJECT_STRUCTURE
                submissionReportRepository.deleteBySubmissionIdExceptProjectStructure(submission.id)

                if (!buildReport.mavenExecutionFailed()) {

                    submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                            reportKey = Indicator.COMPILATION.code, reportValue = if (buildReport.compilationErrors().isEmpty()) "OK" else "NOK"))

                    if (buildReport.compilationErrors().isEmpty()) {

                        if (buildReport.checkstyleValidationActive()) {
                            submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                                    reportKey = Indicator.CHECKSTYLE.code, reportValue = if (buildReport.checkstyleErrors().isEmpty()) "OK" else "NOK"))
                        }


                        // PMD not yet implemented
//                submissionReportRepository.deleteBySubmissionIdAndReportKey(submission.id, "Code Quality (PMD)")
//                submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
//                        reportKey = "Code Quality (PMD)", reportValue = if (buildReport.PMDerrors().isEmpty()) "OK" else "NOK"))



                        if (assignment.acceptsStudentTests) {

                            val junitSummary = buildReport.junitSummaryAsObject(TestType.STUDENT)
                            val indicator =
                                    if (buildReport.hasJUnitErrors(TestType.STUDENT) == true) {
                                        "NOK"
                                    } else if (junitSummary?.numTests == null || junitSummary.numTests < assignment.minStudentTests!!) {
                                        // student hasn't implemented enough tests
                                        "Not Enough Tests"
                                    } else {
                                        "OK"
                                    }

                            submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                                    reportKey = Indicator.STUDENT_UNIT_TESTS.code,
                                    reportValue = indicator,
                                    reportProgress = junitSummary?.progress,
                                    reportGoal = junitSummary?.numTests))
                        }

                        if (buildReport.hasJUnitErrors(TestType.TEACHER) != null) {
                            val junitSummary = buildReport.junitSummaryAsObject(TestType.TEACHER)
                            submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                                    reportKey = Indicator.TEACHER_UNIT_TESTS.code,
                                    reportValue = if (buildReport.hasJUnitErrors(TestType.TEACHER) == true) "NOK" else "OK",
                                    reportProgress = junitSummary?.progress,
                                    reportGoal = junitSummary?.numTests))
                        }

                        if (buildReport.hasJUnitErrors(TestType.HIDDEN) != null) {
                            val junitSummary = buildReport.junitSummaryAsObject(TestType.HIDDEN)
                            submissionReportRepository.save(SubmissionReport(submissionId = submission.id,
                                    reportKey = Indicator.HIDDEN_UNIT_TESTS.code,
                                    reportValue = if (buildReport.hasJUnitErrors(TestType.HIDDEN) == true) "NOK" else "OK",
                                    reportProgress = junitSummary?.progress,
                                    reportGoal = junitSummary?.numTests))
                        }
                    }
                }

                //TODO: Build report search for maven output? What about gradle?
                val buildReportDB = buildReportRepository.save(org.dropProject.dao.BuildReport(
                        buildReport = buildReport.mavenOutput()))
                submission.buildReportId = buildReportDB.id

                // store the junit reports in the DB
                File("${projectFolder}/target/surefire-reports")
                        .walkTopDown()
                        .filter { it -> it.name.endsWith(".xml") }
                        .forEach {
                            val report = JUnitReport(submissionId = submission.id, fileName = it.name,
                                    xmlReport = it.readText(Charset.forName("UTF-8")))
                            jUnitReportRepository.save(report)
                        }

                //NEW: Added verification for compiler Maven to make sure no errors happen in test coverage
                if (assignment.compiler == Compiler.MAVEN && assignment.calculateStudentTestsCoverage && hasCoverageReport(projectFolder)) {

                    // this may seem stupid but I have to rename TestTeacher files to something that will make junit ignore them,
                    // then invoke maven again, so that the coverage report is based
                    // on the sole execution of the student unit tests (otherwise, it will include coverage from
                    // the teacher tests) and finally rename TestTeacher files back
                    File("${projectFolder}/src/test")
                            .walkTopDown()
                            .filter { it -> it.name.startsWith("TestTeacher") }
                            .forEach {
                                Files.move(it.toPath(), it.toPath().resolveSibling("${it.name}.ignore"))
                            }


                    LOG.info("[${authorsStr}] Started maven invocation again (for coverage)")

                    val mavenResultCoverage = mavenInvoker.run(projectFolder, realPrincipalName, assignment.maxMemoryMb)
                    if (!mavenResultCoverage.expiredByTimeout) {
                        LOG.info("[${authorsStr}] Finished maven invocation (for coverage)")

                        // check again the result of the tests
                        val buildReportCoverage = buildReportBuilder.build(mavenResult.outputLines, projectFolder.absolutePath,
                                assignment, submission)
                        if (buildReportCoverage.hasJUnitErrors(TestType.STUDENT) == true) {
                            LOG.warn("Submission ${submission.id} failed executing student tests when isolated from teacher tests")
                        } else {
                            if (File("${projectFolder}/target/site/jacoco").exists()) {
                                // store the jacoco reports in the DB
                                File("${projectFolder}/target/site/jacoco")
                                        .listFiles()
                                        .filter { it -> it.name.endsWith(".csv") }
                                        .forEach {
                                            val report = JacocoReport(submissionId = submission.id, fileName = it.name,
                                                    csvReport = it.readText(Charset.forName("UTF-8")))
                                            jacocoReportRepository.save(report)
                                        }
                            } else {
                                LOG.warn("Submission ${submission.id} failed measuring coverage because the folder " +
                                        "[${projectFolder}/target/site/jacoco] doesn't exist")
                            }
                        }

                    }

                    File("${projectFolder}/src/test")
                            .walkTopDown()
                            .filter { it -> it.name.endsWith(".ignore") }
                            .forEach {
                                Files.move(it.toPath(), it.toPath().resolveSibling(it.name.replace(".ignore","")))
                            }

                }

                if (!rebuildByTeacher) {
                    submission.setStatus(SubmissionStatus.VALIDATED, dontUpdateStatusDate = dontChangeStatusDate)
                } else {
                    submission.setStatus(SubmissionStatus.VALIDATED_REBUILT, dontUpdateStatusDate = dontChangeStatusDate)
                }
            }
        }

        submission.gitSubmissionId?.let {
            gitSubmissionId ->
                val gitSubmission = gitSubmissionRepository.getById(gitSubmissionId)
                gitSubmission.lastSubmissionId = submission.id
        }

        submissionRepository.save(submission)
    }

    /**
     * Checks an [Assignment], performing all the relevant steps and generates the respective [BuildReport].
     *
     * @param assignmentFolder is a File
     * @param assignment is an [Assignment]
     * @param principalName is a String
     *
     * @return a [BuildReport] or null
     */
    fun checkAssignment(assignmentFolder: File, assignment: Assignment, principalName: String?) : BuildReport? {

        LOG.info("Started maven invocation to check ${assignment.id}");

        val mavenResult = mavenInvoker.run(assignmentFolder, principalName, assignment.maxMemoryMb)

        LOG.info("Finished maven invocation to check ${assignment.id}");

        if (!mavenResult.expiredByTimeout) {
            LOG.info("Maven invoker OK for ${assignment.id}")
            return buildReportBuilder.build(mavenResult.outputLines, assignmentFolder.absolutePath, assignment)
        } else {
            LOG.info("Maven invoker aborted by timeout for ${assignment.id}")
            return null
        }

    }

}
