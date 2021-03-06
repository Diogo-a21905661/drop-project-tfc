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
package org.dropProject.data

import org.dropProject.dao.Assignment
import org.dropProject.dao.AssignmentTestMethod
import org.dropProject.dao.Language
import org.dropProject.dao.Compiler
import org.dropProject.services.JUnitMethodResult
import org.dropProject.services.JUnitMethodResultType
import org.dropProject.services.JUnitResults
import org.dropProject.services.JacocoResults
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Enum representing the types of tests that DP supports:
 * - Student tests - unit tests written by the students to test their own work;
 * - Teacher - unit tests written by the teachers to test the student's work; The detailed results of these tests are
 * always shown to the students.
 * - Hidden Teacher Tests - unit tests written by the teachers; The results of these tests can be partially visible to
 * the students or not (configurable when creating the assignment).
 */
enum class TestType {
    STUDENT, TEACHER, HIDDEN
}

/**
 * Represents the output that is generated by Maven for a certain [Submission]'s code.
 *
 * @property outputLines is a List of String, where each String is a line of the Maven's build process's output
 * @property projectFolder is a String
 * @property assignment identifies the [Assignment] that Submission targetted.
 * @property junitResults is a List of [JunitResults] with the result of evaluating the Submission using JUnit tests
 * @property jacocoResults is a List of [JacocoResults] with the result of evaluating the Submission's code coverage
 * @property assignmentTestMethods is a List of [AssignmentTestMethod]. Each object describes on of the executed Unit Tests
 */
data class BuildReport(val outputLines: List<String>,
                       val projectFolder: String,
                       val assignment: Assignment,
                       val junitResults: List<JUnitResults>,
                       val jacocoResults: List<JacocoResults>,
                       val assignmentTestMethods: List<AssignmentTestMethod>) {

    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    //Edit output to show to report
    fun getOutput() : String {
        return outputLines.joinToString(separator = "\n")
    }

     //Create junitSummary for report
     fun junitSummary(testType: TestType = TestType.TEACHER) : String? {
        val junitSummary = junitSummaryAsObject(testType)
        if (junitSummary != null) {
            return "Tests run: ${junitSummary.numTests}, Failures: ${junitSummary.numFailures}, " +
                    "Errors: ${junitSummary.numErrors}, Time elapsed: ${junitSummary.ellapsed} sec"
        } else {
            return null
        }
    }

     /**
     * Creates a summary of the testing results, considering a certain [TestType].
     *
     * @param testType is a [TestType], indicating which tests should be considered (e.g TEACHER tests)
     *
     * @return a [JUnitSummary]
     */
    fun junitSummaryAsObject(testType: TestType = TestType.TEACHER) : JUnitSummary? {
        if (junitResults
                .filter{testType == TestType.TEACHER && it.isTeacherPublic(assignment) ||
                        testType == TestType.STUDENT && it.isStudent(assignment) ||
                        testType == TestType.HIDDEN && it.isTeacherHidden()}
                .isEmpty()) {
            return null
        }

        var totalTests = 0
        var totalErrors = 0
        var totalFailures = 0
        var totalSkipped = 0
        var totalElapsed = 0.0f
        var totalMandatoryOK = 0  // mandatory tests that passed

        for (junitResult in junitResults) {
            if (testType == TestType.TEACHER && junitResult.isTeacherPublic(assignment) ||
                    testType == TestType.STUDENT && junitResult.isStudent(assignment) ||
                    testType == TestType.HIDDEN && junitResult.isTeacherHidden()) {
                totalTests += junitResult.numTests
                totalErrors += junitResult.numErrors
                totalFailures += junitResult.numFailures
                totalSkipped += junitResult.numSkipped
                totalElapsed += junitResult.timeEllapsed

                assignment.mandatoryTestsSuffix?.let {
                    mandatoryTestsSuffix ->
                        totalMandatoryOK += junitResult.junitMethodResults
                            .filter {
                                it.fullMethodName.endsWith(mandatoryTestsSuffix) &&
                                        it.type == JUnitMethodResultType.SUCCESS
                            }
                            .count()
                }
            }
        }

        return JUnitSummary(totalTests, totalFailures, totalErrors, totalSkipped, totalElapsed, totalMandatoryOK)
    }

    /**
     * Calculates the total elapsed time during the execution of the Unit Tests. Considers both the public and the
     * private (hidden) tests.
     *
     * @return a BigDecimal representing the elapsed time
     */
    fun elapsedTimeJUnit() : BigDecimal? {
        var total : BigDecimal? = null
        val junitSummaryTeacher = junitSummaryAsObject(TestType.TEACHER)
        if (junitSummaryTeacher != null) {
            total = junitSummaryTeacher.ellapsed.toBigDecimal()
        }

        val junitSummaryHidden = junitSummaryAsObject(TestType.HIDDEN)
        if (junitSummaryHidden != null && total != null) {
            total += junitSummaryHidden.ellapsed.toBigDecimal()
        }

        return total
    }

     /**
     * Determines if the evaluation resulted in any JUnit errors or failures.
     */
    fun hasJUnitErrors(testType: TestType = TestType.TEACHER) : Boolean? {
        val junitSummary = junitSummaryAsObject(testType)
        if (junitSummary != null) {
            return junitSummary.numErrors > 0 || junitSummary.numFailures > 0
        } else {
            return null
        }
    }
    
    /**
     * Determines if the evaluation resulted in any JUnit errors or failures.
     */
    fun jUnitErrors(testType: TestType = TestType.TEACHER) : String? {
        var result = ""
        for (junitResult in junitResults) {
            if (testType == TestType.TEACHER && junitResult.isTeacherPublic(assignment) ||
                    testType == TestType.STUDENT && junitResult.isStudent(assignment) ||
                    testType == TestType.HIDDEN && junitResult.isTeacherHidden()) {
                result += junitResult.junitMethodResults
                        .filter { it.type != JUnitMethodResultType.SUCCESS && it.type != JUnitMethodResultType.IGNORED }
                        .map { it.filterStacktrace(assignment.packageName.orEmpty()); it }
                        .joinToString(separator = "\n")
            }
        }

        if (result.isEmpty()) {
            return null
        } else {
            return result
        }

        //        if (hasJUnitErrors() == true) {
        //            val testReport = File("${projectFolder}/target/surefire-reports")
        //                    .walkTopDown()
        //                    .filter { it -> it.name.endsWith(".txt") }
        //                    .map { it -> String(Files.readAllBytes(it.toPath()))  }
        //                    .joinToString(separator = "\n")
        //            return testReport
        //        }
        //        return null
    }

    /**
     * Determines if the student's (own) Test class contains at least the minimum number of JUnit tests that are expected
     * by the [Assignment].
     *
     * @return a String with an informative error message or null.
     */
    fun notEnoughStudentTestsMessage() : String? {

        if (!assignment.acceptsStudentTests) {
            throw IllegalArgumentException("This method shouldn't have been called!")
        }

        val junitSummary = junitSummaryAsObject(TestType.STUDENT)

        if (junitSummary == null) {
            return "The submission doesn't include unit tests. " +
                    "The assignment requires a minimum of ${assignment.minStudentTests} tests."
        }

        if (junitSummary.numTests < assignment.minStudentTests!!) {
            return "The submission only includes ${junitSummary.numTests} unit tests. " +
                    "The assignment requires a minimum of ${assignment.minStudentTests} tests."
        }

        return null
    }

    //Get results of tests from assignment test methods repository
    fun testResults() : List<JUnitMethodResult>? {
        if (assignmentTestMethods.isEmpty()) {
            return null  // assignment is not properly configured
        }

        var globalMethodResults = mutableListOf<JUnitMethodResult>()
        for (junitResult in junitResults) {
            if (junitResult.isTeacherPublic(assignment) || junitResult.isTeacherHidden()) {
                globalMethodResults.addAll(junitResult.junitMethodResults)
            }
        }

        var result = mutableListOf<JUnitMethodResult>()
        for (assignmentTest in assignmentTestMethods) {
            var found = false
            for (submissionTest in globalMethodResults) {
                if (submissionTest.methodName.equals(assignmentTest.testMethod) &&
                        submissionTest.getClassName().equals(assignmentTest.testClass)) {
                    result.add(submissionTest)
                    found = true
                    break
                }
            }

            // make sure there are no holes in the tests "matrix"
            if (!found) {
                result.add(JUnitMethodResult.empty())
            }
        }

        return result
    }

    //Check for full execution failed of report
    fun executionFailed() : Boolean {
        //Check for compiler
        if (assignment.compiler == Compiler.GRADLE) {
            return executionFailedGradle()
        }

        return executionFailedMaven()
    }

    //Check for full execution failed of report (Maven)
    private fun executionFailedMaven() : Boolean {        
        // if it has a failed goal other than compiler or surefire (junit), it is a fatal error
        if (outputLines.
                        filter { it.startsWith("[ERROR] Failed to execute goal") }.isNotEmpty()) {
            return outputLines.filter {
                        it.startsWith("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin") ||
                        it.startsWith("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin") ||
                        it.startsWith("[ERROR] Failed to execute goal org.jetbrains.kotlin:kotlin-maven-plugin")
            }.isEmpty()
        }
        return false;
    }

    //Check for full execution failed of report (Gradle)
    private fun executionFailedGradle() : Boolean {        
        // if it has a failed goal other than compiler or surefire (junit), it is a fatal error
        //Havent seen any failures in gradle yet, so for now is fully true
        /*
        if (outputLines.
                        filter { it.startsWith("[ERROR] Failed to execute goal") }.isNotEmpty()) {
            return outputLines.filter {
                        it.startsWith("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin") ||
                        it.startsWith("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin") ||
                        it.startsWith("[ERROR] Failed to execute goal org.jetbrains.kotlin:kotlin-maven-plugin")
            }.isEmpty()
        }
        */

        return false;
    }

    /**
     * Collects from Output the errors related with the Compilation process.
     * Will stop the compilation of everything
     *
     * @return a List of String where each String is a Compilation problem / warning.
     */
    fun compilationErrors() : List<String> {
        //Check for compiler
        if (assignment.compiler == Compiler.GRADLE) {
            return compilationErrorsGradle()
        }

        return compilationErrorsMaven()
    }

    /**
     * Collects from Maven Output the errors related with the Compilation process.
     *
     * @return a List of String where each String is a Compilation problem / warning.
     */
    private fun compilationErrorsMaven() : List<String> {
        LOG.info("Started checking Maven compilation errors for project.")

        var errors = ArrayList<String>()

        /* Android is still in Kotlin */
        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        // parse compilation errors
        run {
            val triggerStartOfCompilationOutput =
                    if (assignment.language == Language.JAVA)
                        "\\[ERROR\\] COMPILATION ERROR :.*".toRegex()
                    else
                        "\\[INFO\\] --- kotlin-maven-plugin:\\d+\\.\\d+\\.\\d+:compile.*".toRegex()

            var startIdx = -1; 
            var endIdx = -1;
            for ((idx, outputLine) in outputLines.withIndex()) {
                if (triggerStartOfCompilationOutput.matches(outputLine)) {
                    startIdx = idx + 1
                    LOG.trace("Found start of compilation output (line $idx)")
                } else if (startIdx > 0) {
                    if (outputLine.startsWith("[INFO] BUILD FAILURE") ||
                            outputLine.startsWith("[INFO] --- ")) {    // no compilation errors on Kotlin
                        endIdx = idx
                        LOG.trace("Found end of compilation output (line $idx)")
                        break
                    }
                }
            }

            if (startIdx > 0 && endIdx > startIdx) {
                errors.addAll(
                    outputLines
                                .subList(startIdx, endIdx)
                                .filter { it -> it.startsWith("[ERROR] ") || it.startsWith("  ") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/main/${folder}/", "") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/test/${folder}/", "[TEST] ") })
            }
        }

        // parse test compilation errors
        run {
            val triggerStartOfTestCompilationOutput =
                //if (language == Language.JAVA) "???" else
                        "\\[ERROR\\] Failed to execute goal org\\.jetbrains\\.kotlin:kotlin-maven-plugin.*test-compile.*".toRegex()

            var startIdx = -1;
            var endIdx = -1;
            for ((idx, outputLine) in outputLines.withIndex()) {
                if (triggerStartOfTestCompilationOutput.matches(outputLine)) {
                    startIdx = idx + 1
                }
                if (outputLine.startsWith("[ERROR] -> [Help 1]")) {
                    endIdx = idx
                }
            }

            if (startIdx > 0) {
                errors.addAll(
                    outputLines
                                .subList(startIdx, endIdx)
                                .filter { it -> it.startsWith("[ERROR] ") || it.startsWith("  ") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/main/${folder}/", "") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/test/${folder}/", "[TEST] ") })
            }
        }

        // check if tests didn't run because of a crash or System.exit(). for the lack of better solution, I'll
        // consider this as a compilation error
        if (outputLines.any { it.contains("The forked VM terminated without properly saying goodbye.") }) {
           when (assignment.language) {
               Language.JAVA -> errors.add("Invalid call to System.exit(). Please remove this instruction")
               Language.KOTLIN ->  errors.add("Invalid call to System.exit() or exitProcess(). Please remove this instruction")
               Language.ANDROID ->  errors.add("Invalid call to System.exit() or exitProcess(). Please remove this instruction")
            }
            }

        LOG.info("Finished checking for build errors -> ${errors}")

        return errors
    }

    /**
     * Collects from Gradle Output the errors related with the Compilation process.
     *
     * @return a List of String where each String is a Compilation problem / warning.
     */
    private fun compilationErrorsGradle() : List<String> {
        var errors = ArrayList<String>()
        //errors.add("Test of error")

        /* Android is still in Kotlin */
        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        /*
        // parse compilation errors
        run {
            val triggerStartOfCompilationOutput =
                    if (assignment.language == Language.JAVA)
                        "\\[ERROR\\] COMPILATION ERROR :.*".toRegex()
                    else
                        "\\[INFO\\] --- kotlin-maven-plugin:\\d+\\.\\d+\\.\\d+:compile.*".toRegex()

            var startIdx = -1; 
            var endIdx = -1;
            for ((idx, outputLine) in outputLines.withIndex()) {
                if (triggerStartOfCompilationOutput.matches(outputLine)) {
                    startIdx = idx + 1
                    LOG.trace("Found start of compilation output (line $idx)")
                } else if (startIdx > 0) {
                    if (outputLine.startsWith("[INFO] BUILD FAILURE") ||
                            outputLine.startsWith("[INFO] --- ")) {    // no compilation errors on Kotlin
                        endIdx = idx
                        LOG.trace("Found end of compilation output (line $idx)")
                        break
                    }
                }
            }

            if (startIdx > 0 && endIdx > startIdx) {
                errors.addAll(
                    outputLines
                                .subList(startIdx, endIdx)
                                .filter { it -> it.startsWith("[ERROR] ") || it.startsWith("  ") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/main/${folder}/", "") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/test/${folder}/", "[TEST] ") })
            }
        }

        // parse test compilation errors
        run {
            //if (language == Language.JAVA) "???" else
            val triggerStartOfTestCompilationOutput = "\\[ERROR\\] Failed to execute goal org\\.jetbrains\\.kotlin:kotlin-maven-plugin.*test-compile.*".toRegex()

            var startIdx = -1;
            var endIdx = -1;
            for ((idx, outputLine) in outputLines.withIndex()) {
                if (triggerStartOfTestCompilationOutput.matches(outputLine)) {
                    startIdx = idx + 1
                }
                if (outputLine.startsWith("[ERROR] -> [Help 1]")) {
                    endIdx = idx
                }
            }

            if (startIdx > 0) {
                errors.addAll(
                    outputLines
                                .subList(startIdx, endIdx)
                                .filter { it -> it.startsWith("[ERROR] ") || it.startsWith("  ") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/main/${folder}/", "") }
                                .map { it -> it.replace("[ERROR] ${projectFolder}/src/test/${folder}/", "[TEST] ") })
            }
        }

        // check if tests didn't run because of a crash or System.exit(). for the lack of better solution, I'll
        // consider this as a compilation error
        if (outputLines.any { it.contains("The forked VM terminated without properly saying goodbye.") }) {
           when (assignment.language) {
               Language.JAVA -> errors.add("Invalid call to System.exit(). Please remove this instruction")
               Language.KOTLIN ->  errors.add("Invalid call to System.exit() or exitProcess(). Please remove this instruction")
               Language.ANDROID ->  errors.add("Invalid call to System.exit() or exitProcess(). Please remove this instruction")
            }
        }
        */

        LOG.info("Finished checking for build errors -> ${errors}")
        return errors
    }

    //Check if checkstyle validation is active to specific language
    fun checkstyleValidationActive() : Boolean {
        //Check for compiler
        if (assignment.compiler == Compiler.GRADLE) {
            return checkstyleValidationActiveGradle()
        }

        return checkstyleValidationActiveMaven()
    }

    //Check if checkstyle validation is active to specific language
    private fun checkstyleValidationActiveMaven() : Boolean {
        when (assignment.language) {
            Language.JAVA -> {
                for (outputLine in outputLines) {
                    if (outputLine.startsWith("[INFO] Starting audit...")) {
                        return true
                    }
                }

                return false
            }
            Language.KOTLIN -> {
                for (outputLine in outputLines) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        return true
                    }
                }

                return false
            }
            //NEW: Added the case for ANDROID language (same as the KOTLIN variant)
             Language.ANDROID -> {
                for (outputLine in outputLines) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        return true
                    }
                }

                return false
            }
        }
    }

    //Check if checkstyle validation is active to specific language
    //TO DO: Create checkstyle validation, for now always true
    private fun checkstyleValidationActiveGradle() : Boolean {
        return true

        /*
        //TO DO: Have to add validation to when for Gradle
        when (assignment.language) {
            Language.JAVA -> {
                for (outputLine in outputLines) {
                    if (outputLine.startsWith("[INFO] Starting audit...")) {
                        return true
                    }
                }

                return false
            }
            Language.KOTLIN -> {
                for (outputLine in outputLines) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        return true
                    }
                }

                return false
            }
            //NEW: Added the case for ANDROID language (same as the KOTLIN variant)
             Language.ANDROID -> {
                for (outputLine in outputLines) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        return true
                    }
                }

                return false
            }
        }
        */
    }

    /**
     * Collects from Output the errors related with the CheckStyle plugin / rules.
     * Doesnt appear in Assignment creation
     *
     * @return a List of String where each String is a CheckStyle problem / warning.
     */
    fun checkstyleErrors() : List<String> {
        //Check for compiler
        if (assignment.compiler == Compiler.GRADLE) {
            return checkstyleErrorsGradle()
        }

        return checkstyleErrorsMaven()
    }

    /**
     * Collects from Maven Output the errors related with the CheckStyle plugin / rules.
     * 
     *
     * @return a List of String where each String is a CheckStyle problem / warning.
     */
    private fun checkstyleErrorsMaven() : List<String> {
        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        when (assignment.language) {
            Language.JAVA -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, outputLine) in outputLines.withIndex()) {
                    if (outputLine.startsWith("[INFO] Starting audit...")) {
                        startIdx = idx + 1
                    }
                    if (outputLine.startsWith("Audit done.")) {
                        endIdx = idx
                    }
                }

                if (startIdx > 0) {
                    return outputLines
                            .subList(startIdx, endIdx)
                            .filter { it -> it.startsWith("[WARN] ") }
                            .map { it -> it.replace("[WARN] ${projectFolder}/src/main/${folder}/", "") }
                } else {
                    return emptyList()
                }
            }

            Language.KOTLIN -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, outputLine) in outputLines.withIndex()) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        startIdx = idx + 1
                    }
                    // depending on the detekt-maven-plugin version, the output is different
                    if (startIdx > 0 &&
                            idx > startIdx + 1 &&
                            (outputLine.startsWith("detekt finished") || outputLine.startsWith("[INFO]"))) {
                        endIdx = idx
                        break
                    }
                }

                if (startIdx > 0) {
                    return outputLines
                            .subList(startIdx, endIdx)
                            .filter { it.startsWith("\t") && !it.startsWith("\t-") }
                            .map { it.replace("\t", "") }
                            .map { it.replace("${projectFolder}/src/main/${folder}/", "") }
                            .map { translateDetektError(it) }
                            .distinct()
                } else {
                    return emptyList()
                }
            }

            //NEW: Added verifier for ANDROID language build in function (same as KOTLIN)
            Language.ANDROID -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, outputLine) in outputLines.withIndex()) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        startIdx = idx + 1
                    }
                    // depending on the detekt-maven-plugin version, the output is different
                    if (startIdx > 0 &&
                            idx > startIdx + 1 &&
                            (outputLine.startsWith("detekt finished") || outputLine.startsWith("[INFO]"))) {
                        endIdx = idx
                        break
                    }
                }

                if (startIdx > 0) {
                    return outputLines
                            .subList(startIdx, endIdx)
                            .filter { it.startsWith("\t") && !it.startsWith("\t-") }
                            .map { it.replace("\t", "") }
                            .map { it.replace("${projectFolder}/src/main/${folder}/", "") }
                            .map { translateDetektError(it) }
                            .distinct()
                } else {
                    return emptyList()
                }
            }
        }
    }

    /**
     * Collects from Gradle Output the errors related with the CheckStyle plugin / rules.
     *
     * @return a List of String where each String is a CheckStyle problem / warning.
     */
    private fun checkstyleErrorsGradle() : List<String> {
        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        return outputLines
            .subList(1, 10)
            .filter { it -> it.startsWith("[INFO] ") }
            .map { it -> it.replace("[IFNO] ${projectFolder}/src/main/${folder}/", "") }
        /*
        when (assignment.language) {
            Language.JAVA -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, outputLine) in outputLines.withIndex()) {
                    if (outputLine.startsWith("[INFO] Starting audit...")) {
                        startIdx = idx + 1
                    }
                    if (outputLine.startsWith("Audit done.")) {
                        endIdx = idx
                    }
                }

                if (startIdx > 0) {
                    return outputLines
                            .subList(startIdx, endIdx)
                            .filter { it -> it.startsWith("[WARN] ") }
                            .map { it -> it.replace("[WARN] ${projectFolder}/src/main/${folder}/", "") }
                } else {
                    return emptyList()
                }
            }

            Language.KOTLIN -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, outputLine) in outputLines.withIndex()) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        startIdx = idx + 1
                    }
                    // depending on the detekt-maven-plugin version, the output is different
                    if (startIdx > 0 &&
                            idx > startIdx + 1 &&
                            (outputLine.startsWith("detekt finished") || outputLine.startsWith("[INFO]"))) {
                        endIdx = idx
                        break
                    }
                }

                if (startIdx > 0) {
                    return outputLines
                            .subList(startIdx, endIdx)
                            .filter { it.startsWith("\t") && !it.startsWith("\t-") }
                            .map { it.replace("\t", "") }
                            .map { it.replace("${projectFolder}/src/main/${folder}/", "") }
                            .map { translateDetektError(it) }
                            .distinct()
                } else {
                    return emptyList()
                }
            }

            //NEW: Added verifier for ANDROID language build in function (same as KOTLIN)
            Language.ANDROID -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, outputLine) in outputLines.withIndex()) {
                    if (outputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        startIdx = idx + 1
                    }
                    // depending on the detekt-maven-plugin version, the output is different
                    if (startIdx > 0 &&
                            idx > startIdx + 1 &&
                            (outputLine.startsWith("detekt finished") || outputLine.startsWith("[INFO]"))) {
                        endIdx = idx
                        break
                    }
                }

                if (startIdx > 0) {
                    return outputLines
                            .subList(startIdx, endIdx)
                            .filter { it.startsWith("\t") && !it.startsWith("\t-") }
                            .map { it.replace("\t", "") }
                            .map { it.replace("${projectFolder}/src/main/${folder}/", "") }
                            .map { translateDetektError(it) }
                            .distinct()
                } else {
                    return emptyList()
                }
            }
        }
        */
    }

    //Check for PMD errors in maven output
    fun PMDerrors() : List<String> {
        return outputLines
                .filter { it -> it.startsWith("[INFO] PMD Failure") }
                .map { it -> it.substring(19) }  // to remove "[INFO] PMD Failure: "
    }

    /**
     * Converts an error generated by the Detekt Kotlin static analysis plugin into a more human readbale / friendly
     * message.
     *
     * @param originalError is a String with the Detekt error message
     * 
     * @return a String with the "converted" error message
     */
    private fun translateDetektError(originalError: String) : String {
        return originalError
                .replace("VariableNaming -", "Nome da vari??vel deve come??ar por letra min??scula. " +
                        "Caso o nome tenha mais do que uma palavra, as palavras seguintes devem ser capitalizadas (iniciadas por uma mai??scula) -")
                .replace("FunctionNaming -", "Nome da fun????o deve come??ar por letra min??scula. " +
                        "Caso o nome tenha mais do que uma palavra, as palavras seguintes devem ser capitalizadas (iniciadas por uma mai??scula) -")
                .replace("FunctionParameterNaming -", "Nome do par??metro de fun????o deve come??ar por letra min??scula. " +
                        "Caso o nome tenha mais do que uma palavra, as palavras seguintes devem ser capitalizadas (iniciadas por uma mai??scula) -")
                .replace("VariableMinLength -", "Nome da vari??vel demasiado pequeno -")
                .replace("VarCouldBeVal -", "Vari??vel imut??vel declarada com var -")
                .replace("MandatoryBracesIfStatements -", "Instru????o 'if' sem chaveta -")
                .replace("ComplexCondition -", "Condi????o demasiado complexa -")
                .replace("StringLiteralDuplication -", "String duplicada. Deve ser usada uma constante -")
                .replace("NestedBlockDepth -", "Demasiados n??veis de blocos dentro de blocos -")
                .replace("UnsafeCallOnNullableType -", "N??o ?? permitido usar o !! pois pode causar crashes -")
                .replace("MaxLineLength -", "Linha demasiado comprida -")
                .replace("LongMethod -", "Fun????o com demasiadas linhas de c??digo -")
                .replace("ForbiddenKeywords -", "Utiliza????o de instru????es proibidas -")
    }
}